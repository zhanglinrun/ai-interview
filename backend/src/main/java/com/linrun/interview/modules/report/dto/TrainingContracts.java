package com.linrun.interview.modules.report.dto;

import com.linrun.interview.modules.report.model.TrainingStatus;
import com.linrun.interview.modules.report.model.TrainingType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class TrainingContracts {

  private TrainingContracts() {
  }

  public record CreateTrainingRequest(
      @NotBlank @Size(max = 191) String capabilityAtomId,
      @NotNull TrainingType trainingType,
      @Size(max = 4000) String question,
      @Size(max = 20) List<@NotBlank String> evidenceScopes
  ) {
    public CreateTrainingRequest {
      evidenceScopes = evidenceScopes == null ? List.of("PLATFORM") : List.copyOf(evidenceScopes);
    }
  }

  public record CompleteTrainingRequest(
      @NotNull @Min(0) @Max(100) Integer score,
      Boolean objectivePassed,
      Boolean hintUsed,
      Boolean answerViewed,
      @Min(0) @Max(20) Integer redoCount,
      @Size(max = 500) String observation
  ) {
    public CompleteTrainingRequest {
      hintUsed = Boolean.TRUE.equals(hintUsed);
      answerViewed = Boolean.TRUE.equals(answerViewed);
      redoCount = redoCount == null ? 0 : redoCount;
    }
  }

  public record TrainingInteractionRequest(
      Boolean hintUsed,
      Boolean answerViewed,
      Boolean redo
  ) {
    public TrainingInteractionRequest {
      hintUsed = Boolean.TRUE.equals(hintUsed);
      answerViewed = Boolean.TRUE.equals(answerViewed);
      redo = Boolean.TRUE.equals(redo);
    }
  }

  public record TrainingTaskView(
      String taskId,
      String reportId,
      String capabilityAtomId,
      TrainingType trainingType,
      TrainingStatus status,
      Long sourceQuestionId,
      String question,
      String questionVersion,
      List<String> evidenceScopes,
      boolean hintUsed,
      boolean answerViewed,
      int redoCount,
      Integer resultScore,
      LocalDateTime createdAt,
      LocalDateTime completedAt
  ) {
    public TrainingTaskView {
      evidenceScopes = evidenceScopes == null ? List.of() : List.copyOf(evidenceScopes);
    }
  }
}
