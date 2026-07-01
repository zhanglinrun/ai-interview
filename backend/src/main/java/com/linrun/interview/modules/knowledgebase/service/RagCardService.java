package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.interview.model.SessionListItemDTO;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.interviewschedule.model.InterviewScheduleDTO;
import com.linrun.interview.modules.interviewschedule.service.InterviewScheduleService;
import com.linrun.interview.modules.knowledgebase.model.RagCardChoiceDTO;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.InterviewIntent;
import com.linrun.interview.modules.resume.model.ResumeListItemDTO;
import com.linrun.interview.modules.resume.service.ResumeHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RAG 流式交互卡片（对齐 know-engine CARD 协议，前缀 {@code card:}/{@code card_choice:}）。
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
  private final InterviewSkillService interviewSkillService;
  private final ObjectMapper objectMapper;

  /**
   * 按意图推送交互卡片（简历 / 日程 / 面试报告 / 方向选择等）。
   */
  public Optional<Flux<String>> maybeInteractionCards(IntentRecognitionResult intent) {
    return maybeResumeSelectionCards(intent)
        .or(() -> maybeResumeDetailCard(intent))
        .or(() -> maybeScheduleSelectionCards(intent))
        .or(() -> maybeInterviewSessionSelectionCards(intent))
        .or(() -> maybeInterviewReportHintCard(intent))
        .or(() -> maybeSkillSelectionCards(intent));
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

  public Optional<Flux<String>> maybeScheduleSelectionCards(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.SCHEDULE) {
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

  public Optional<Flux<String>> maybeInterviewSessionSelectionCards(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.INTERVIEW_PREP) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().sessionId() != null) {
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
    String sessionId = String.valueOf(intent.entities().sessionId());
    return Optional.of(Flux.just(CARD_PREFIX
        + "已定位面试会话 " + sessionId
        + "，可在「模拟面试 → 面试记录」查看完整报告。"));
  }

  public Optional<Flux<String>> maybeSkillSelectionCards(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()) {
      return Optional.empty();
    }
    InterviewIntent resolved = intent.resolvedIntent();
    if (resolved != InterviewIntent.INTERVIEW_PREP && resolved != InterviewIntent.CAREER) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().skill() != null
        && !intent.entities().skill().isBlank()) {
      return Optional.empty();
    }
    List<InterviewSkillService.SkillDTO> skills = interviewSkillService.getAllSkills();
    if (skills.size() <= 1) {
      return Optional.empty();
    }
    List<RagCardChoiceDTO> choices = skills.stream()
        .limit(8)
        .map(skill -> new RagCardChoiceDTO(skill.id(), skill.name(), "skill"))
        .toList();
    return buildChoiceCard("请选择面试/职业方向", choices);
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
