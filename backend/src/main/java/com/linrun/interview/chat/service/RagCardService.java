package com.linrun.interview.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.vo.SessionListItemDTO;
import com.linrun.interview.business.service.InterviewPersistenceService;
import com.linrun.interview.business.service.InterviewTopic;
import com.linrun.interview.business.service.InterviewTopicCatalog;
import com.linrun.interview.business.vo.InterviewScheduleDTO;
import com.linrun.interview.business.service.InterviewScheduleService;
import com.linrun.interview.chat.dto.RagCardChoiceDTO;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.constant.InterviewIntent;
import com.linrun.interview.business.vo.ResumeListItemDTO;
import com.linrun.interview.business.service.ResumeHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RAG 流式交互卡片（对齐业界实践 CARD 协议，前缀 {@code card:}/{@code card_choice:}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCardService {

  static final String CARD_PREFIX = "card:";
  static final String CARD_CHOICE_PREFIX = "card_choice:";

  private final ResumeHistoryService resumeHistoryService;
  private final InterviewScheduleService interviewScheduleService;
  private final InterviewPersistenceService interviewPersistenceService;
  private final InterviewTopicCatalog interviewTopicCatalog;
  private final ObjectMapper objectMapper;

  /**
   * 按意图推送交互卡片（简历 / 日程 / 面试报告 / 方向选择等）。
   */
  public Optional<Flux<String>> maybeInteractionCards(IntentRecognitionResult intent, String question) {
    return maybeResumeSelectionCards(intent)
        .or(() -> maybeResumeDetailCard(intent))
        .or(() -> maybeScheduleSelectionCards(intent, question))
        .or(() -> maybeInterviewSessionSelectionCards(intent, question))
        .or(() -> maybeInterviewReportHintCard(intent))
        .or(() -> maybeTopicSelectionCards(intent, question));
  }

  public Optional<Flux<String>> maybeResumeSelectionCards(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.RESUME_STATS) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().resumeId() != null) {
      return Optional.empty();
    }
    if (UserContext.getUserId() == null) {
      return Optional.empty();
    }
    List<ResumeListItemDTO> resumes = resumeHistoryService.getAllResumes();
    if (resumes.isEmpty()) {
      return Optional.of(Flux.just(CARD_PREFIX + "您还没有上传简历，请先在「简历管理」上传后再查询。"));
    }
    if (resumes.size() == 1) {
      return Optional.empty();
    }
    return buildChoiceCard("请先选择要查询的简历", resumes.stream()
        .map(resume -> new RagCardChoiceDTO(
            String.valueOf(resume.id()),
            resume.filename() != null && !resume.filename().isBlank()
                ? resume.filename() : "简历 #" + resume.id(),
            "resume"))
        .toList());
  }

  public Optional<Flux<String>> maybeResumeDetailCard(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.RESUME_STATS) {
      return Optional.empty();
    }
    if (intent.entities() == null || intent.entities().resumeId() == null) {
      return Optional.empty();
    }
    Long resumeId = intent.entities().resumeId();
    Optional<ResumeListItemDTO> resumeOpt = resumeHistoryService.getAllResumes().stream()
        .filter(r -> resumeId.equals(r.id()))
        .findFirst();
    if (resumeOpt.isEmpty()) {
      return Optional.empty();
    }
    ResumeListItemDTO resume = resumeOpt.get();
    String name = resume.filename() != null ? resume.filename() : "简历 #" + resume.id();
    String scoreText = resume.latestScore() != null
        ? "最新评分 " + resume.latestScore() + " 分"
        : "尚未完成 AI 评分";
    String msg = "已定位简历「" + name + "」（ID=" + resume.id() + "），" + scoreText
        + "。可在「简历管理」查看详情，或继续提问。";
    return Optional.of(Flux.just(CARD_PREFIX + msg));
  }

  public Optional<Flux<String>> maybeScheduleSelectionCards(IntentRecognitionResult intent, String question) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.SCHEDULE) {
      return Optional.empty();
    }
    // 卡片追问已带「面试安排 ID=…」时不再重复弹卡，避免选完又短路。
    if (mentionsScheduleId(question)) {
      return Optional.empty();
    }
    if (UserContext.getUserId() == null) {
      return Optional.empty();
    }
    List<InterviewScheduleDTO> schedules = interviewScheduleService.getAll(null, null, null);
    if (schedules == null || schedules.size() <= 1) {
      return Optional.empty();
    }
    List<RagCardChoiceDTO> choices = new ArrayList<>(schedules.size());
    for (InterviewScheduleDTO schedule : schedules) {
      String label = (schedule.getCompanyName() != null ? schedule.getCompanyName() : "未知公司")
          + " · " + (schedule.getPosition() != null ? schedule.getPosition() : "岗位待定");
      choices.add(new RagCardChoiceDTO(String.valueOf(schedule.getId()), label, "schedule"));
    }
    return buildChoiceCard("请选择要查询的面试安排", choices);
  }

  public Optional<Flux<String>> maybeInterviewSessionSelectionCards(
      IntentRecognitionResult intent,
      String question
  ) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.INTERVIEW_PREP) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().sessionId() != null) {
      return Optional.empty();
    }
    if (!asksForInterviewReport(question)) {
      return Optional.empty();
    }
    if (UserContext.getUserId() == null) {
      return Optional.empty();
    }
    List<SessionListItemDTO> sessions = interviewPersistenceService.findAll().stream()
        .map(SessionListItemDTO::from)
        .filter(s -> s.status() == InterviewSessionEntity.SessionStatus.EVALUATED
            || s.status() == InterviewSessionEntity.SessionStatus.COMPLETED)
        .toList();
    if (sessions.size() <= 1) {
      return Optional.empty();
    }
    List<RagCardChoiceDTO> choices = new ArrayList<>(sessions.size());
    for (SessionListItemDTO session : sessions) {
      String label = (session.skillId() != null ? session.skillId() : "模拟面试")
          + (session.overallScore() != null ? " · " + session.overallScore() + "分" : "");
      choices.add(new RagCardChoiceDTO(session.sessionId(), label, "session"));
    }
    return buildChoiceCard("请选择要查看的面试报告", choices);
  }

  public Optional<Flux<String>> maybeInterviewReportHintCard(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.INTERVIEW_PREP) {
      return Optional.empty();
    }
    if (intent.entities() == null || intent.entities().sessionId() == null) {
      return Optional.empty();
    }
    String sessionId = intent.entities().sessionId();
    return Optional.of(Flux.just(CARD_PREFIX
        + "已定位面试会话 " + sessionId
        + "，可在「模拟面试 → 面试记录」查看完整报告。"));
  }

  public Optional<Flux<String>> maybeTopicSelectionCards(IntentRecognitionResult intent, String question) {
    if (intent == null || !intent.related()) {
      return Optional.empty();
    }
    InterviewIntent resolved = intent.resolvedIntent();
    if (resolved != InterviewIntent.INTERVIEW_PREP && resolved != InterviewIntent.CAREER) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().jobTrack() != null
        && !intent.entities().jobTrack().isBlank()) {
      return Optional.empty();
    }
    if (!asksForTopicSelection(question)) {
      return Optional.empty();
    }
    List<InterviewTopic> topics = interviewTopicCatalog.listTopics();
    if (topics.size() <= 1) {
      return Optional.empty();
    }
    List<RagCardChoiceDTO> choices = topics.stream()
        .limit(8)
        .map(topic -> new RagCardChoiceDTO(topic.id(), topic.name(), "jobTrack"))
        .toList();
    return buildChoiceCard("请选择目标岗位方向", choices);
  }

  private boolean asksForTopicSelection(String question) {
    if (question == null || question.isBlank()) {
      return false;
    }
    String normalized = question.strip().toLowerCase();
    boolean selectionRequest = normalized.contains("选择")
        || normalized.contains("哪个")
        || normalized.contains("哪些")
        || normalized.contains("有什么")
        || normalized.contains("推荐");
    boolean topicRequest = normalized.contains("岗位")
        || normalized.contains("方向")
        || normalized.contains("主题");
    boolean interviewStartRequest = normalized.contains("模拟面试")
        || ((normalized.contains("开始") || normalized.contains("来一场"))
            && normalized.contains("面试"));
    return selectionRequest && topicRequest || interviewStartRequest;
  }

  private boolean asksForInterviewReport(String question) {
    if (question == null || question.isBlank()) {
      return false;
    }
    String normalized = question.strip().toLowerCase();
    boolean reportRequest = normalized.contains("报告")
        || normalized.contains("复盘")
        || normalized.contains("总结")
        || normalized.contains("成绩")
        || normalized.contains("评分");
    return reportRequest;
  }

  private boolean mentionsScheduleId(String question) {
    if (question == null || question.isBlank()) {
      return false;
    }
    return question.matches("(?s).*(?:面试安排|日程|schedule)\\s*(?:id|ID)?\\s*[:：#=\\-]?\\s*\\d+.*");
  }

  private Optional<Flux<String>> buildChoiceCard(String prompt, List<RagCardChoiceDTO> choices) {
    try {
      String json = objectMapper.writeValueAsString(choices);
      return Optional.of(Flux.just(CARD_PREFIX + prompt, CARD_CHOICE_PREFIX + json));
    } catch (JsonProcessingException e) {
      log.warn("序列化卡片选项失败: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }
}
