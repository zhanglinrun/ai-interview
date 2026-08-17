package com.linrun.interview.business.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity.SessionStatus;
import com.linrun.interview.business.service.CandidateMemoryService.CandidateMemoryProfileDTO;
import com.linrun.interview.business.vo.AskedTurnSummary;
import com.linrun.interview.business.vo.InterviewMemoryView;
import com.linrun.interview.business.vo.InterviewMemoryView.CompressedMemoryView;
import com.linrun.interview.business.vo.InterviewMemoryView.CompressedTurn;
import com.linrun.interview.business.vo.InterviewMemoryView.LongTermMemoryItem;
import com.linrun.interview.business.vo.InterviewMemoryView.ShortTermMemoryView;
import com.linrun.interview.business.vo.InterviewMemoryView.ShortTermTurn;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.infra.redis.RedisChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 把已有三层记忆收成一个只读视图。不新造记忆源，也不让 LLM 再压一轮摘要。
 *
 * <ul>
 *   <li>短期：本场近 1～2 轮问答原文（头尾截断）；Interviewer 窗口仍在 Redis</li>
 *   <li>压缩：已答主问题的 {@link AskedTurnSummary}（主题 + 结构信号 + 跟进）</li>
 *   <li>长期：评估分跨场投影，注入下场 Planner</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewMemoryService {

  private static final int SHORT_TERM_TEXT_CHARS = 280;

  private final InterviewPersistenceService persistenceService;
  private final RedisChatMemoryStore chatMemoryStore;
  private final CandidateMemoryService candidateMemoryService;
  private final AgentOrchestrationProperties properties;
  private final ObjectMapper objectMapper;

  public InterviewMemoryView getMemory(String skillId) {
    Long userId = UserContext.requireUserId();
    InterviewSessionEntity focus = pickFocusSession(skillId);
    List<InterviewQuestionDTO> questions = parseQuestions(focus);
    return new InterviewMemoryView(
        buildShortTerm(focus, questions),
        buildCompressed(focus, questions),
        buildLongTerm(userId, skillId));
  }

  private InterviewSessionEntity pickFocusSession(String skillId) {
    List<InterviewSessionEntity> sessions = persistenceService.findAll().stream()
        .filter(session -> matchesSkill(session, skillId))
        .toList();
    return sessions.stream().filter(this::isLive).findFirst()
        .or(() -> sessions.stream().filter(this::hasAnsweredMain).findFirst())
        .orElse(sessions.isEmpty() ? null : sessions.getFirst());
  }

  private ShortTermMemoryView buildShortTerm(
      InterviewSessionEntity session, List<InterviewQuestionDTO> questions) {
    int windowSize = Math.max(2, properties.getMemoryWindow());
    if (session == null) {
      return new ShortTermMemoryView(null, null, false, windowSize, 0, List.of());
    }
    int agentMessageCount = 0;
    try {
      agentMessageCount = chatMemoryStore.getMessages(session.getSessionId()).size();
    } catch (Exception e) {
      log.debug("读取短期 Agent 窗口失败: sessionId={}", session.getSessionId(), e);
    }
    int pairLimit = Math.max(1, windowSize / 2);
    return new ShortTermMemoryView(
        session.getSessionId(),
        session.getSkillId(),
        isLive(session),
        windowSize,
        agentMessageCount,
        recentAnswerTurns(questions, pairLimit));
  }

  private CompressedMemoryView buildCompressed(
      InterviewSessionEntity session, List<InterviewQuestionDTO> questions) {
    if (session == null) {
      return CompressedMemoryView.empty();
    }
    List<CompressedTurn> turns = AskedTurnSummary.fromAnsweredMains(questions).stream()
        .map(summary -> new CompressedTurn(
            summary.questionIndex(),
            summary.topicSummary(),
            summary.followUpAction(),
            summary.answerSignals().meaningfulChars(),
            summary.answerSignals().hasReasoning(),
            summary.answerSignals().hasExample(),
            summary.answerSignals().hasTradeOff(),
            summary.answerSignals().expressesUncertainty()))
        .toList();
    return new CompressedMemoryView(session.getSessionId(), session.getSkillId(), turns);
  }

  private List<LongTermMemoryItem> buildLongTerm(Long userId, String skillId) {
    return candidateMemoryService.getProfile(userId, skillId).stream()
        .map(this::toLongTerm)
        .toList();
  }

  private LongTermMemoryItem toLongTerm(CandidateMemoryProfileDTO profile) {
    return new LongTermMemoryItem(
        profile.topic(),
        profile.capabilityAtomId(),
        profile.masteryLevel(),
        profile.verificationState(),
        profile.averageScore(),
        profile.observationCount(),
        profile.sessionCount(),
        profile.latestEvidence(),
        profile.lastAt());
  }

  private List<ShortTermTurn> recentAnswerTurns(List<InterviewQuestionDTO> questions, int pairLimit) {
    List<InterviewQuestionDTO> answered = questions.stream()
        .filter(question -> question.userAnswer() != null && !question.userAnswer().isBlank())
        .toList();
    if (answered.isEmpty()) {
      return List.of();
    }
    int from = Math.max(0, answered.size() - pairLimit);
    List<ShortTermTurn> turns = new ArrayList<>();
    for (InterviewQuestionDTO question : answered.subList(from, answered.size())) {
      turns.add(new ShortTermTurn("ASSISTANT",
          PromptTextUtil.headTailTruncate(question.question(), SHORT_TERM_TEXT_CHARS)));
      turns.add(new ShortTermTurn("USER",
          PromptTextUtil.headTailTruncate(question.userAnswer(), SHORT_TERM_TEXT_CHARS)));
    }
    return List.copyOf(turns);
  }

  private List<InterviewQuestionDTO> parseQuestions(InterviewSessionEntity session) {
    if (session == null || session.getQuestionsJson() == null || session.getQuestionsJson().isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(session.getQuestionsJson(), new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("解析面试题目失败，记忆视图按空题目处理: sessionId={}", session.getSessionId(), e);
      return List.of();
    }
  }

  private boolean matchesSkill(InterviewSessionEntity session, String skillId) {
    return skillId == null || skillId.isBlank()
        || skillId.equals(session.getSkillId());
  }

  private boolean isLive(InterviewSessionEntity session) {
    SessionStatus status = session.getStatus();
    return status == SessionStatus.CREATED
        || status == SessionStatus.IN_PROGRESS
        || status == SessionStatus.PAUSED;
  }

  private boolean hasAnsweredMain(InterviewSessionEntity session) {
    return parseQuestions(session).stream().anyMatch(question -> !question.isFollowUp()
        && question.userAnswer() != null
        && !question.userAnswer().isBlank());
  }
}
