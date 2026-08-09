package com.linrun.interview.business.vo;

import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.business.constant.CapabilityState;
import com.linrun.interview.business.constant.ReportStatus;
import com.linrun.interview.business.constant.TrainingType;
import java.time.LocalDateTime;
import java.util.List;

public final class ReportContracts {

  private ReportContracts() {
  }

  public record ObjectiveFact(
      Long questionId,
      int questionIndex,
      String stage,
      String question,
      String answer,
      String assessmentStatus,
      Integer technicalCorrectness,
      Integer completeness,
      String factualConsistency,
      EvidenceStatus evidenceStatus,
      Double confidence,
      String judgeStatus,
      Integer passedCount,
      Integer totalCount,
      String codingLanguage,
      Long executionTimeMs,
      Long memoryKb,
      String feedback,
      List<String> evidenceIds,
      boolean sourceAvailable
  ) {
    public ObjectiveFact {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record SummaryContent(
      String overallFeedback,
      List<String> strengths,
      List<String> improvements
  ) {
    public SummaryContent {
      overallFeedback = sanitizeReportText(overallFeedback);
      strengths = sanitizeReportItems(strengths);
      improvements = sanitizeReportItems(improvements);
    }
  }

  private static List<String> sanitizeReportItems(List<String> items) {
    if (items == null) {
      return List.of();
    }
    return items.stream()
        .map(ReportContracts::sanitizeReportText)
        .filter(item -> item != null && !item.isBlank())
        .limit(5)
        .toList();
  }

  private static String sanitizeReportText(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return value
        .replaceAll(
            "(?i)候选人未提供有效回答[（(]\\s*[`\"']?answer[`\"']?\\s*"
                + "(?:字段)?\\s*(?:为|是|=|:)\\s*null\\s*[)）]",
            "后续题目未作答")
        .replaceAll(
            "(?i)[`\"']?answer[`\"']?\\s*(?:字段)?\\s*(?:为|是|=|:)\\s*null",
            "未作答")
        .replaceAll("(?i)\\bnull\\b", "暂无记录")
        .replace("assessmentStatus", "评估状态")
        .replace("judgeStatus", "判题状态")
        .replace("passedCount", "通过用例数")
        .replace("totalCount", "用例总数")
        .strip();
  }

  public record CapabilityGap(
      String capabilityAtomId,
      String capabilityName,
      String reason,
      Long sourceQuestionId,
      List<String> evidenceRecordIds,
      TrainingType trainingType,
      String trainingTaskId
  ) {
    public CapabilityGap {
      evidenceRecordIds = evidenceRecordIds == null ? List.of() : List.copyOf(evidenceRecordIds);
    }
  }

  public record ReportView(
      String reportId,
      String sessionId,
      ReportStatus status,
      List<ObjectiveFact> objectiveFacts,
      SummaryContent summary,
      List<CapabilityGap> gaps,
      String failureCode,
      String failureDetail,
      int generationAttempt,
      boolean retryable,
      LocalDateTime createdAt,
      LocalDateTime completedAt
  ) {
    public ReportView {
      objectiveFacts = objectiveFacts == null ? List.of() : List.copyOf(objectiveFacts);
      gaps = gaps == null ? List.of() : gaps.stream().limit(3).toList();
    }
  }

  public record CapabilityProfileView(
      String capabilityAtomId,
      String capabilityName,
      CapabilityState state,
      boolean reviewRequired,
      int evidenceCount,
      List<String> recentEvidenceRecordIds,
      LocalDateTime lastEvidenceAt,
      LocalDateTime updatedAt
  ) {
    public CapabilityProfileView {
      recentEvidenceRecordIds = recentEvidenceRecordIds == null
          ? List.of() : List.copyOf(recentEvidenceRecordIds);
    }
  }
}
