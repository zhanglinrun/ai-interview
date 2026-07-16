package com.linrun.interview.modules.jobinterview.model;

import com.linrun.interview.common.evidence.EvidenceStatus;
import java.util.List;

/** 技术评价与候选人事实核验分离；证据不足时 factualConsistency 必须为 UNVERIFIED。 */
public record AnswerAssessment(
    Integer technicalCorrectness,
    Integer completeness,
    String factualConsistency,
    EvidenceStatus evidenceStatus,
    double confidence,
    RecommendedAction recommendedAction,
    String rationale,
    List<String> objectiveEvidenceIds,
    boolean pendingReview
) {
  public AnswerAssessment {
    factualConsistency = factualConsistency == null ? "UNVERIFIED" : factualConsistency;
    evidenceStatus = evidenceStatus == null ? EvidenceStatus.NONE : evidenceStatus;
    recommendedAction = recommendedAction == null
        ? RecommendedAction.SWITCH_TOPIC : recommendedAction;
    rationale = rationale == null ? "" : rationale;
    objectiveEvidenceIds = objectiveEvidenceIds == null
        ? List.of() : List.copyOf(objectiveEvidenceIds);
  }

  public static AnswerAssessment pending(EvidenceStatus evidenceStatus, String reason) {
    return new AnswerAssessment(
        null, null, "UNVERIFIED", evidenceStatus, 0.0d,
        RecommendedAction.SWITCH_TOPIC, reason, List.of(), true);
  }
}
