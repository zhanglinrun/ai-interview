package com.linrun.interview.modules.interview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.CommonConstants.InterviewDefaults;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.interview.mapper.InterviewAnswerMapper;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.model.HistoricalQuestion;
import com.linrun.interview.modules.interview.model.InterviewAnswerEntity;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

  private final InterviewSessionMapper interviewSessionMapper;
  private final InterviewAnswerMapper interviewAnswerMapper;
  private final ResumeEntityMapper resumeEntityMapper;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                            int totalQuestions,
                                            List<InterviewQuestionDTO> questions,
                                            String llmProvider,
                                            String skillId,
                                            String difficulty) {
    Long userId = UserContext.requireUserId();
    try {
      InterviewSessionEntity session = new InterviewSessionEntity();
      session.setUserId(userId);
      session.setSessionId(sessionId);
      session.setTotalQuestions(totalQuestions);
      session.setCurrentQuestionIndex(0);
      session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
      session.setQuestionsJson(objectMapper.writeValueAsString(questions));
      session.setLlmProvider(llmProvider != null ? llmProvider : "default");
      session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
      session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
      session.setCreatedAt(LocalDateTime.now());

      if (resumeId != null) {
        ResumeEntity resume = EntityQueries.byUserAndId(
            resumeEntityMapper, userId, resumeId, ResumeEntity::getUserId, ResumeEntity::getId)
          .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        session.setResume(resume);
      }

      InterviewSessionEntity saved = MapperUtils.save(interviewSessionMapper, session);
      log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}", sessionId, skillId, resumeId);
      return saved;
    } catch (JsonProcessingException e) {
      log.error("序列化问题列表失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败", e);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setStatus(status);
      if (status == InterviewSessionEntity.SessionStatus.COMPLETED
          || status == InterviewSessionEntity.SessionStatus.EVALUATED) {
        session.setCompletedAt(LocalDateTime.now());
      }
      MapperUtils.save(interviewSessionMapper, session);
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setEvaluateStatus(status);
      if (error != null) {
        session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
      } else {
        session.setEvaluateError(null);
      }
      MapperUtils.save(interviewSessionMapper, session);
      log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateCurrentQuestionIndex(String sessionId, int index) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setCurrentQuestionIndex(index);
      session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
      MapperUtils.save(interviewSessionMapper, session);
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                          String question, String category,
                                          String userAnswer, int score, String feedback) {
    InterviewSessionEntity session = findSessionEntityBySessionId(sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));

    InterviewAnswerEntity answer = findAnswerBySessionAndIndex(session.getId(), questionIndex)
      .orElseGet(() -> {
        InterviewAnswerEntity created = new InterviewAnswerEntity();
        created.setSessionId(session.getId());
        created.setUserId(session.getUserId());
        created.setQuestionIndex(questionIndex);
        created.setAnsweredAt(LocalDateTime.now());
        return created;
      });

    answer.setUserId(session.getUserId());
    answer.setQuestion(question);
    answer.setCategory(category);
    answer.setUserAnswer(userAnswer);
    answer.setScore(score);
    answer.setFeedback(feedback);

    InterviewAnswerEntity saved = MapperUtils.save(interviewAnswerMapper, answer);
    log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}",
        sessionId, questionIndex, score);
    return saved;
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveReport(String sessionId, InterviewReportDTO report) {
    try {
      Optional<InterviewSessionEntity> sessionOpt = findSessionEntityBySessionId(sessionId);
      if (sessionOpt.isEmpty()) {
        log.warn("会话不存在: {}", sessionId);
        return;
      }

      InterviewSessionEntity session = sessionOpt.get();
      session.setOverallScore(report.overallScore());
      session.setOverallFeedback(report.overallFeedback());
      session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
      session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
      session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
      session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
      session.setCompletedAt(LocalDateTime.now());
      MapperUtils.save(interviewSessionMapper, session);

      List<InterviewAnswerEntity> existingAnswers = findAnswersBySessionId(sessionId);
      Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
        .collect(Collectors.toMap(
          InterviewAnswerEntity::getQuestionIndex,
          a -> a,
          (a1, a2) -> a1
        ));

      Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
        .collect(Collectors.toMap(
          InterviewReportDTO.ReferenceAnswer::questionIndex,
          r -> r,
          (r1, r2) -> r1
        ));

      List<InterviewAnswerEntity> answersToSave = new ArrayList<>();
      for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
        InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());
        if (answer == null) {
          answer = new InterviewAnswerEntity();
          answer.setSessionId(session.getId());
          answer.setUserId(session.getUserId());
          answer.setQuestionIndex(eval.questionIndex());
          answer.setQuestion(eval.question());
          answer.setCategory(eval.category());
          answer.setUserAnswer(null);
          answer.setAnsweredAt(LocalDateTime.now());
          log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
        }

        answer.setUserId(session.getUserId());
        answer.setScore(eval.score());
        answer.setFeedback(eval.feedback());

        InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
        if (refAns != null) {
          answer.setReferenceAnswer(refAns.referenceAnswer());
          if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
            answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
          }
        }
        answersToSave.add(answer);
      }

      for (InterviewAnswerEntity answer : answersToSave) {
        MapperUtils.save(interviewAnswerMapper, answer);
      }
      log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
          sessionId, report.overallScore(), answersToSave.size());
    } catch (JsonProcessingException e) {
      log.error("序列化报告失败: {}", e.getMessage(), e);
    }
  }

  public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
    return findSessionByUserAndSessionId(UserContext.requireUserId(), sessionId);
  }

  public Optional<InterviewSessionEntity> findBySessionIdInternal(String sessionId) {
    return findSessionEntityBySessionId(sessionId).map(this::attachResumeIfPresent);
  }

  public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
    return interviewSessionMapper.selectList(Wrappers.<InterviewSessionEntity>lambdaQuery()
      .eq(InterviewSessionEntity::getUserId, UserContext.requireUserId())
      .eq(InterviewSessionEntity::getResumeId, resumeId)
      .orderByDesc(InterviewSessionEntity::getCreatedAt));
  }

  public List<InterviewSessionEntity> findAll() {
    return EntityQueries.listByUserIdOrderByDesc(
      interviewSessionMapper,
      UserContext.requireUserId(),
      InterviewSessionEntity::getUserId,
      InterviewSessionEntity::getCreatedAt);
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteSessionsByResumeId(Long resumeId) {
    List<InterviewSessionEntity> sessions = findByResumeId(resumeId);
    for (InterviewSessionEntity session : sessions) {
      deleteAnswersBySessionId(session.getId());
      interviewSessionMapper.deleteById(session.getId());
    }
    if (!sessions.isEmpty()) {
      log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteSessionBySessionId(String sessionId) {
    InterviewSessionEntity session = findSessionByUserAndSessionId(UserContext.requireUserId(), sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    deleteAnswersBySessionId(session.getId());
    interviewSessionMapper.deleteById(session.getId());
    log.info("已删除面试会话: sessionId={}", sessionId);
  }

  public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
    List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
      InterviewSessionEntity.SessionStatus.CREATED,
      InterviewSessionEntity.SessionStatus.IN_PROGRESS
    );
    return MapperUtils.selectOneOptional(interviewSessionMapper,
      Wrappers.<InterviewSessionEntity>lambdaQuery()
        .eq(InterviewSessionEntity::getUserId, UserContext.requireUserId())
        .eq(InterviewSessionEntity::getResumeId, resumeId)
        .in(InterviewSessionEntity::getStatus, unfinishedStatuses)
        .orderByDesc(InterviewSessionEntity::getCreatedAt)
        .last("LIMIT 1"));
  }

  public void ensureResumeAccessible(Long resumeId) {
    if (resumeId != null && !EntityQueries.existsByUserAndId(
        resumeEntityMapper, UserContext.requireUserId(), resumeId,
        ResumeEntity::getUserId, ResumeEntity::getId)) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
    }
  }

  public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
    return findSessionEntityBySessionId(sessionId)
      .map(session -> interviewAnswerMapper.selectList(
        Wrappers.<InterviewAnswerEntity>lambdaQuery()
          .eq(InterviewAnswerEntity::getSessionId, session.getId())
          .orderByAsc(InterviewAnswerEntity::getQuestionIndex)))
      .orElse(List.of());
  }

  private static final int MAX_HISTORICAL_QUESTIONS = 60;

  public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
    Long userId = UserContext.requireUserId();
    ensureResumeAccessible(resumeId);
    List<InterviewSessionEntity> sessions;
    if (resumeId != null) {
      sessions = interviewSessionMapper.selectList(
        Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getUserId, userId)
          .eq(InterviewSessionEntity::getResumeId, resumeId)
          .eq(InterviewSessionEntity::getSkillId, skillId)
          .orderByDesc(InterviewSessionEntity::getCreatedAt)
          .last("LIMIT 10"));
    } else {
      sessions = interviewSessionMapper.selectList(
        Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getUserId, userId)
          .eq(InterviewSessionEntity::getSkillId, skillId)
          .orderByDesc(InterviewSessionEntity::getCreatedAt)
          .last("LIMIT 10"));
    }

    log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<HistoricalQuestion> result = sessions.stream()
      .map(InterviewSessionEntity::getQuestionsJson)
      .filter(json -> json != null && !json.isEmpty())
      .flatMap(json -> {
        try {
          List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
            new TypeReference<List<InterviewQuestionDTO>>() {});
          return questions.stream()
            .filter(q -> !q.isFollowUp())
            .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
        } catch (Exception e) {
          log.error("解析历史问题JSON失败", e);
          return Stream.<HistoricalQuestion>empty();
        }
      })
      .filter(hq -> seen.add(hq.question()))
      .limit(MAX_HISTORICAL_QUESTIONS)
      .toList();

    log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
      result.stream().collect(Collectors.groupingBy(
        hq -> hq.type() != null ? hq.type() : "GENERAL",
        Collectors.counting())));

    return result;
  }

  private Optional<InterviewSessionEntity> findSessionEntityBySessionId(String sessionId) {
    return EntityQueries.selectOne(interviewSessionMapper, InterviewSessionEntity::getSessionId, sessionId);
  }

  private Optional<InterviewSessionEntity> findSessionByUserAndSessionId(Long userId, String sessionId) {
    return MapperUtils.selectOneOptional(interviewSessionMapper,
      Wrappers.<InterviewSessionEntity>lambdaQuery()
        .eq(InterviewSessionEntity::getUserId, userId)
        .eq(InterviewSessionEntity::getSessionId, sessionId));
  }

  private Optional<InterviewAnswerEntity> findAnswerBySessionAndIndex(Long sessionPkId, int questionIndex) {
    return MapperUtils.selectOneOptional(interviewAnswerMapper,
      Wrappers.<InterviewAnswerEntity>lambdaQuery()
        .eq(InterviewAnswerEntity::getSessionId, sessionPkId)
        .eq(InterviewAnswerEntity::getQuestionIndex, questionIndex));
  }

  private void deleteAnswersBySessionId(Long sessionPkId) {
    interviewAnswerMapper.delete(Wrappers.<InterviewAnswerEntity>lambdaQuery()
      .eq(InterviewAnswerEntity::getSessionId, sessionPkId));
  }

  private InterviewSessionEntity attachResumeIfPresent(InterviewSessionEntity session) {
    if (session.getResumeId() != null) {
      ResumeEntity resume = resumeEntityMapper.selectById(session.getResumeId());
      session.setResume(resume);
    }
    return session;
  }
}
