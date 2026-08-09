package com.linrun.interview.business.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.vo.JobInterviewContracts.AssessmentView;
import com.linrun.interview.business.vo.JobInterviewContracts.EventView;
import com.linrun.interview.business.vo.JobInterviewContracts.QuestionView;
import com.linrun.interview.business.vo.JobInterviewContracts.SessionView;
import com.linrun.interview.business.constant.AnswerAssessmentStatus;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JobInterviewViewAssembler {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;

  public JobInterviewViewAssembler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public SessionView session(
      JobInterviewSessionEntity entity,
      JobInterviewQuestionEntity currentQuestion,
      int answeredQuestions,
      int totalQuestions
  ) {
    return new SessionView(
        entity.getSessionId(), entity.getStatus(), value(entity.getSessionVersion()),
        entity.getCurrentStage(), entity.getJobDescriptionId(),
        integer(entity.getJobDescriptionVersion()), entity.getCapabilityTemplateCode(),
        entity.getCapabilityTemplateVersion(), entity.getPlanVersion(), entity.getPromptVersion(),
        entity.getGithubCommitSha(), entity.getCodingLanguage(),
        Boolean.TRUE.equals(entity.getPersonalKnowledgeEnabled()),
        read(entity.getDegradedReasonsJson(), STRING_LIST, List.of()),
        question(currentQuestion), answeredQuestions, totalQuestions,
        entity.getStageDeadlineAt(), entity.getSoftDeadlineAt(), entity.getResumeExpiresAt(),
        canResume(entity), entity.getActiveCommandId());
  }

  private boolean canResume(JobInterviewSessionEntity entity) {
    LocalDateTime expiresAt = entity.getResumeExpiresAt();
    return entity.getStatus() == JobInterviewSessionStatus.PAUSED
        && integer(entity.getContinuationCount()) < 1
        && expiresAt != null
        && expiresAt.isAfter(LocalDateTime.now());
  }

  public QuestionView question(JobInterviewQuestionEntity entity) {
    if (entity == null) {
      return null;
    }
    return new QuestionView(
        entity.getId(), integer(entity.getQuestionIndex()), entity.getStage(),
        entity.getQuestionText(), integer(entity.getBudgetSeconds()),
        Boolean.TRUE.equals(entity.getFollowUp()), entity.getParentQuestionId(),
        entity.getCapabilityAtomId());
  }

  public AssessmentView assessment(JobInterviewAssessmentPort.AssessmentOutcome outcome) {
    if (outcome == null || outcome.assessment() == null) {
      return null;
    }
    var value = outcome.assessment();
    AnswerAssessmentStatus status = value.pendingReview()
        ? AnswerAssessmentStatus.NEEDS_REVIEW : AnswerAssessmentStatus.COMPLETED;
    return new AssessmentView(
        status, value.technicalCorrectness(), value.completeness(),
        value.factualConsistency(), value.evidenceStatus(), value.confidence(),
        value.recommendedAction(), value.rationale(), value.objectiveEvidenceIds(),
        outcome.latencyMs(), outcome.inputTokens(), outcome.outputTokens(),
        outcome.retryCount(), outcome.degradedReason());
  }

  public EventView event(InterviewSessionEventEntity entity) {
    return new EventView(
        entity.getId(), entity.getEventType(), value(entity.getSessionVersion()),
        read(entity.getPayloadJson(), OBJECT_MAP, Map.of()), entity.getCreatedAt(),
        entity.getSourceTraceId());
  }

  private <T> T read(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      return fallback;
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  private int integer(Integer value) {
    return value == null ? 0 : value;
  }
}
