package com.linrun.interview.business.vo;

import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.business.constant.AnswerAssessmentStatus;
import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.constant.InterviewCommandType;
import com.linrun.interview.business.constant.JobCodingLanguage;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.constant.PreparationStatus;
import com.linrun.interview.business.constant.RecommendedAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 岗位实战 REST/SSE 的不可变边界对象。 */
public final class JobInterviewContracts {

  private JobInterviewContracts() {
  }

  public record CreatePreparationRequest(
      @NotNull @Positive Long jobDescriptionId,
      @Positive Long resumeId,
      @Positive Long githubRepositoryId,
      @Size(max = 20) List<@Positive Long> knowledgeBaseIds,
      Boolean includePersonalMaterials,
      @NotNull JobCodingLanguage codingLanguage,
      Boolean regenerate
  ) {
    public CreatePreparationRequest {
      knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
      includePersonalMaterials = Boolean.TRUE.equals(includePersonalMaterials);
      regenerate = Boolean.TRUE.equals(regenerate);
    }
  }

  public record PreparationView(
      String runId,
      PreparationStatus status,
      Long jobDescriptionId,
      String jobTitle,
      String templateCode,
      String templateVersion,
      JobCodingLanguage codingLanguage,
      boolean personalKnowledgeEnabled,
      boolean resumeBound,
      boolean githubBound,
      List<StageView> stages,
      List<String> degradedReasons,
      Map<String, String> dependencyStatus,
      String sessionId,
      Long sessionVersion,
      String failureCode,
      String failureDetail,
      LocalDateTime createdAt,
      LocalDateTime completedAt,
      boolean reused
  ) {
    public PreparationView {
      stages = stages == null ? List.of() : List.copyOf(stages);
      degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
      dependencyStatus = dependencyStatus == null ? Map.of() : Map.copyOf(dependencyStatus);
    }
  }

  public record StageView(JobInterviewStage stage, int budgetSeconds) {
  }

  public record CommandEnvelope(
      @NotBlank @Size(max = 64) String commandId,
      @NotNull @Positive Long expectedSessionVersion
  ) {
  }

  public record SubmitAnswerCommand(
      @NotBlank @Size(max = 64) String commandId,
      @NotNull @Positive Long expectedSessionVersion,
      @NotNull @Positive Long questionId,
      @NotBlank @Size(max = 12000) String answer
  ) {
  }

  public record ClarificationCommand(
      @NotBlank @Size(max = 64) String commandId,
      @NotNull @Positive Long expectedSessionVersion,
      @Size(max = 500) String question
  ) {
  }

  public record CodeCommand(
      @NotBlank @Size(max = 64) String commandId,
      @NotNull @Positive Long expectedSessionVersion,
      @NotNull @Positive Long questionId,
      @NotBlank @Size(max = 50000) String sourceCode
  ) {
  }

  public record AbortCommand(
      @NotBlank @Size(max = 64) String commandId,
      @NotNull @Positive Long expectedSessionVersion,
      @Size(max = 255) String reason
  ) {
  }

  public record CommandResult(
      String commandId,
      InterviewCommandType commandType,
      InterviewCommandStatus commandStatus,
      String sessionId,
      long sessionVersion,
      JobInterviewSessionStatus sessionStatus,
      JobInterviewStage stage,
      String message,
      QuestionView currentQuestion,
      AssessmentView assessment,
      Long eventId,
      boolean duplicate,
      List<String> degradedReasons
  ) {
    public CommandResult {
      degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
    }

    public CommandResult asDuplicate() {
      return new CommandResult(
          commandId, commandType, commandStatus, sessionId, sessionVersion,
          sessionStatus, stage, message, currentQuestion, assessment, eventId, true,
          degradedReasons);
    }
  }

  public record AssessmentView(
      AnswerAssessmentStatus status,
      Integer technicalCorrectness,
      Integer completeness,
      String factualConsistency,
      EvidenceStatus evidenceStatus,
      double confidence,
      RecommendedAction recommendedAction,
      String rationale,
      List<String> objectiveEvidenceIds,
      Long latencyMs,
      Integer inputTokens,
      Integer outputTokens,
      Integer retryCount,
      String degradedReason
  ) {
    public AssessmentView {
      objectiveEvidenceIds = objectiveEvidenceIds == null
          ? List.of() : List.copyOf(objectiveEvidenceIds);
    }
  }

  public record SessionView(
      String sessionId,
      JobInterviewSessionStatus status,
      long sessionVersion,
      JobInterviewStage stage,
      Long jobDescriptionId,
      int jobDescriptionVersion,
      String capabilityTemplateCode,
      String capabilityTemplateVersion,
      String planVersion,
      String promptVersion,
      String githubCommitSha,
      JobCodingLanguage codingLanguage,
      boolean personalKnowledgeEnabled,
      List<String> degradedReasons,
      QuestionView currentQuestion,
      int answeredQuestions,
      int totalQuestions,
      LocalDateTime stageDeadlineAt,
      LocalDateTime softDeadlineAt,
      LocalDateTime resumeExpiresAt,
      boolean canResume,
      String activeCommandId
  ) {
    public SessionView {
      degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
    }
  }

  public record QuestionView(
      Long questionId,
      int questionIndex,
      JobInterviewStage stage,
      String question,
      int budgetSeconds,
      boolean followUp,
      Long parentQuestionId,
      String capabilityName
  ) {
  }

  public record CodeDraftView(
      Long questionId,
      JobCodingLanguage language,
      String functionSignature,
      String sourceCode,
      String sourceHash,
      String judgeStatus,
      String judgeSubmissionId,
      LocalDateTime updatedAt,
      LocalDateTime submittedAt
  ) {
  }

  public record EventView(
      Long eventId,
      String eventType,
      long sessionVersion,
      Map<String, Object> payload,
      LocalDateTime createdAt,
      String sourceTraceId
  ) {
    public EventView(Long eventId, String eventType, long sessionVersion,
                     Map<String, Object> payload, LocalDateTime createdAt) {
      this(eventId, eventType, sessionVersion, payload, createdAt, null);
    }
    public EventView {
      payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
  }
}
