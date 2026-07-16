package com.linrun.interview.modules.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.modules.jobinterview.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.modules.jobinterview.model.AnswerAssessment;
import com.linrun.interview.modules.jobinterview.model.AnswerAssessmentStatus;
import com.linrun.interview.modules.jobinterview.model.InterviewCodeDraftEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewAnswerEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewStage;
import com.linrun.interview.modules.jobinterview.model.RecommendedAction;
import com.linrun.interview.modules.knowledgebase.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.modules.knowledgebase.model.EvidenceSnapshotEntity;
import com.linrun.interview.modules.report.dto.ReportContracts.ObjectiveFact;
import com.linrun.interview.modules.report.model.CapabilityEvidenceEntity;
import com.linrun.interview.modules.report.model.CapabilityEvidenceSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportFactAssembler {

  private final JobInterviewQuestionMapper questionMapper;
  private final JobInterviewAnswerMapper answerMapper;
  private final InterviewCodeDraftMapper draftMapper;
  private final EvidenceSnapshotMapper snapshotMapper;
  private final ObjectMapper objectMapper;

  public Assembly assemble(JobInterviewSessionEntity session, String reportId) {
    List<JobInterviewQuestionEntity> questions = questionMapper.selectList(
        Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
            .eq(JobInterviewQuestionEntity::getUserId, session.getUserId())
            .eq(JobInterviewQuestionEntity::getSessionId, session.getId())
            .orderByAsc(JobInterviewQuestionEntity::getSortOrder));
    List<JobInterviewAnswerEntity> answers = answerMapper.selectList(
        Wrappers.<JobInterviewAnswerEntity>lambdaQuery()
            .eq(JobInterviewAnswerEntity::getUserId, session.getUserId())
            .eq(JobInterviewAnswerEntity::getSessionId, session.getId()));
    List<InterviewCodeDraftEntity> drafts = draftMapper.selectList(
        Wrappers.<InterviewCodeDraftEntity>lambdaQuery()
            .eq(InterviewCodeDraftEntity::getUserId, session.getUserId())
            .eq(InterviewCodeDraftEntity::getSessionId, session.getId()));

    Map<Long, JobInterviewAnswerEntity> answerByQuestion = new HashMap<>();
    Map<Integer, JobInterviewAnswerEntity> answerByIndex = new HashMap<>();
    answers.forEach(answer -> {
      if (answer.getQuestionId() != null) {
        answerByQuestion.put(answer.getQuestionId(), answer);
      }
      if (answer.getQuestionIndex() != null) {
        answerByIndex.put(answer.getQuestionIndex(), answer);
      }
    });
    Map<Long, InterviewCodeDraftEntity> draftByQuestion = new HashMap<>();
    drafts.forEach(draft -> draftByQuestion.put(draft.getQuestionId(), draft));
    Map<String, Boolean> snapshotAvailability = snapshotAvailability(session.getUserId(), questions);

    List<ObjectiveFact> facts = new ArrayList<>();
    List<CapabilityEvidenceEntity> evidence = new ArrayList<>();
    for (JobInterviewQuestionEntity question : questions) {
      JobInterviewAnswerEntity answer = answerByQuestion.get(question.getId());
      if (answer == null) {
        answer = answerByIndex.get(question.getQuestionIndex());
      }
      InterviewCodeDraftEntity draft = draftByQuestion.get(question.getId());
      JudgeFact judge = parseJudge(draft);
      AnswerAssessment assessment = parseAssessment(question.getStage(), answer, judge);
      List<String> evidenceIds = answer == null
          ? readIds(question.getEvidenceIdsJson())
          : readIds(answer.getObjectiveEvidenceIdsJson());
      boolean sourceAvailable = evidenceIds.isEmpty()
          || snapshotAvailability.getOrDefault(question.getEvidenceSnapshotId(), false);

      facts.add(new ObjectiveFact(
          question.getId(), value(question.getQuestionIndex()),
          question.getStage() == null ? null : question.getStage().name(),
          question.getQuestionText(), answer == null ? null : answer.getUserAnswer(),
          answer == null || answer.getAssessmentStatus() == null
              ? "PENDING" : answer.getAssessmentStatus().name(),
          assessment.technicalCorrectness(), assessment.completeness(),
          assessment.factualConsistency(), assessment.evidenceStatus(),
          assessment.confidence(), judge.status(), judge.passedCount(), judge.totalCount(),
          judge.codingLanguage(), judge.executionTimeMs(), judge.memoryKb(),
          answer == null ? null : answer.getFeedback(), evidenceIds, sourceAvailable));

      if (answer != null && question.getCapabilityAtomId() != null
          && !question.getCapabilityAtomId().isBlank()) {
        evidence.add(toEvidence(session, reportId, question, answer, assessment, judge, evidenceIds));
      }
    }
    return new Assembly(facts, evidence);
  }

  private CapabilityEvidenceEntity toEvidence(
      JobInterviewSessionEntity session,
      String reportId,
      JobInterviewQuestionEntity question,
      JobInterviewAnswerEntity answer,
      AnswerAssessment assessment,
      JudgeFact judge,
      List<String> evidenceIds
  ) {
    boolean assessmentComplete = answer.getAssessmentStatus() == AnswerAssessmentStatus.COMPLETED
        && !assessment.pendingReview();
    Boolean objectivePassed = objectivePassed(question.getStage(), judge.status());
    LocalDateTime occurredAt = answer.getAnsweredAt() == null
        ? LocalDateTime.now() : answer.getAnsweredAt();
    String stableKey = reportId + ":" + question.getId();
    String evidenceRecordId = UUID.nameUUIDFromBytes(
        stableKey.getBytes(StandardCharsets.UTF_8)).toString();
    return CapabilityEvidenceEntity.builder()
        .evidenceRecordId(evidenceRecordId)
        .userId(session.getUserId())
        .reportId(reportId)
        .sessionId(session.getId())
        .questionId(question.getId())
        .capabilityAtomId(question.getCapabilityAtomId())
        .sourceType(CapabilityEvidenceSource.JOB_INTERVIEW)
        .difficulty(session.getDifficulty())
        .technicalScore(assessment.technicalCorrectness())
        .completenessScore(assessment.completeness())
        .objectivePassed(objectivePassed)
        .confidence(BigDecimal.valueOf(Math.max(0.0d, Math.min(1.0d, assessment.confidence()))))
        .evidenceStatus(assessment.evidenceStatus())
        .evidenceRefsJson(writeJson(evidenceIds))
        .observation(truncate(firstNonBlank(answer.getFeedback(), assessment.rationale()), 500))
        .eligibleForPromotion(assessmentComplete)
        .hintUsed(false)
        .answerViewed(false)
        .redoCount(0)
        .occurredAt(occurredAt)
        .createdAt(LocalDateTime.now())
        .build();
  }

  private AnswerAssessment parseAssessment(
      JobInterviewStage stage,
      JobInterviewAnswerEntity answer,
      JudgeFact judge
  ) {
    if (stage == JobInterviewStage.ALGORITHM) {
      return algorithmAssessment(answer, judge);
    }
    if (answer == null || answer.getAssessmentJson() == null
        || answer.getAssessmentJson().isBlank()) {
      EvidenceStatus status = answer == null || answer.getEvidenceStatus() == null
          ? EvidenceStatus.NONE : answer.getEvidenceStatus();
      return AnswerAssessment.pending(status, "该题尚未完成结构化评价");
    }
    try {
      return objectMapper.readValue(answer.getAssessmentJson(), AnswerAssessment.class);
    } catch (Exception e) {
      EvidenceStatus status = answer.getEvidenceStatus() == null
          ? EvidenceStatus.NONE : answer.getEvidenceStatus();
      return AnswerAssessment.pending(status, "结构化评价无法解析，标记为待评估");
    }
  }

  private AnswerAssessment algorithmAssessment(
      JobInterviewAnswerEntity answer,
      JudgeFact judge
  ) {
    String status = judge.status();
    if (status == null || status.isBlank()) {
      return AnswerAssessment.pending(EvidenceStatus.NONE, "该题尚未完成客观判题");
    }
    String normalized = status.toUpperCase();
    if ("ACCEPTED".equals(normalized)) {
      return new AnswerAssessment(
          100, 100, "UNVERIFIED", EvidenceStatus.NONE, 1.0d,
          RecommendedAction.SWITCH_TOPIC,
          firstNonBlank(answer == null ? null : answer.getFeedback(), "客观判题通过"),
          List.of(), false);
    }
    if (List.of("QUEUED", "PROCESSING", "UNAVAILABLE", "INTERNAL_ERROR")
        .contains(normalized)) {
      return AnswerAssessment.pending(
          EvidenceStatus.NONE,
          firstNonBlank(answer == null ? null : answer.getFeedback(), "客观判题尚未完成"));
    }
    return new AnswerAssessment(
        0, 0, "UNVERIFIED", EvidenceStatus.NONE, 1.0d,
        RecommendedAction.REMEDIATE,
        firstNonBlank(answer == null ? null : answer.getFeedback(), "客观判题未通过"),
        List.of(), false);
  }

  private JudgeFact parseJudge(InterviewCodeDraftEntity draft) {
    if (draft == null) {
      return new JudgeFact(null, null, null, null, null, null);
    }
    String status = draft.getJudgeStatus();
    Integer passed = null;
    Integer total = null;
    Long executionTimeMs = null;
    Long memoryKb = null;
    if (draft.getJudgeResultJson() != null && !draft.getJudgeResultJson().isBlank()) {
      try {
        JsonNode node = objectMapper.readTree(draft.getJudgeResultJson());
        status = textOr(node, "status", status);
        passed = intOrNull(node, "passedCount");
        total = intOrNull(node, "totalCount");
        executionTimeMs = longOrNull(node, "timeMs");
        memoryKb = longOrNull(node, "memoryKb");
      } catch (Exception ignored) {
        // judgeStatus 列仍是可展示的客观状态；损坏 JSON 不伪造计数。
      }
    }
    String language = draft.getLanguage() == null ? null : switch (draft.getLanguage()) {
      case JAVA21 -> "Java";
      case PYTHON3 -> "Python";
    };
    return new JudgeFact(status, passed, total, language, executionTimeMs, memoryKb);
  }

  private Boolean objectivePassed(JobInterviewStage stage, String judgeStatus) {
    if (stage != JobInterviewStage.ALGORITHM || judgeStatus == null || judgeStatus.isBlank()) {
      return null;
    }
    return switch (judgeStatus.toUpperCase()) {
      case "ACCEPTED" -> true;
      case "QUEUED", "PROCESSING", "UNAVAILABLE", "INTERNAL_ERROR" -> null;
      default -> false;
    };
  }

  private Map<String, Boolean> snapshotAvailability(
      Long userId,
      List<JobInterviewQuestionEntity> questions
  ) {
    List<String> ids = questions.stream()
        .map(JobInterviewQuestionEntity::getEvidenceSnapshotId)
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    snapshotMapper.selectList(Wrappers.<EvidenceSnapshotEntity>lambdaQuery()
            .eq(EvidenceSnapshotEntity::getUserId, userId)
            .in(EvidenceSnapshotEntity::getSnapshotId, ids))
        .forEach(snapshot -> result.put(
            snapshot.getSnapshotId(), Boolean.TRUE.equals(snapshot.getSourceAvailable())));
    return result;
  }

  private List<String> readIds(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (Exception e) {
      return List.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception e) {
      return "[]";
    }
  }

  private String textOr(JsonNode node, String field, String fallback) {
    String value = node.path(field).asText();
    return value == null || value.isBlank() ? fallback : value;
  }

  private Integer intOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asInt();
  }

  private Long longOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength
        ? normalized : normalized.substring(0, maxLength);
  }

  public record Assembly(
      List<ObjectiveFact> facts,
      List<CapabilityEvidenceEntity> capabilityEvidence
  ) {
    public Assembly {
      facts = facts == null ? List.of() : List.copyOf(facts);
      capabilityEvidence = capabilityEvidence == null
          ? List.of() : List.copyOf(capabilityEvidence);
    }
  }

  private record JudgeFact(
      String status,
      Integer passedCount,
      Integer totalCount,
      String codingLanguage,
      Long executionTimeMs,
      Long memoryKb
  ) {
  }
}
