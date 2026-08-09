package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.rag.model.EvidenceStatus;
import java.util.List;

/** 准备完成后不可变的计划快照；API 在开场前不会返回该对象。 */
public record JobInterviewPlanSnapshot(
    String planVersion,
    String promptVersion,
    String templateCode,
    String templateVersion,
    String rubricCode,
    String rubricVersion,
    String modelSnapshot,
    List<PlannedQuestion> questions,
    List<String> evidenceSnapshotIds,
    List<String> degradedReasons
) {
  public JobInterviewPlanSnapshot {
    questions = questions == null ? List.of() : List.copyOf(questions);
    evidenceSnapshotIds = evidenceSnapshotIds == null
        ? List.of() : List.copyOf(evidenceSnapshotIds);
    degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
  }

  public record PlannedQuestion(
      int questionIndex,
      int sortOrder,
      JobInterviewStage stage,
      String questionType,
      String question,
      String capabilityAtomId,
      String capabilityAtomVersion,
      String questionTemplateCode,
      String questionTemplateVersion,
      String rubricCode,
      String rubricVersion,
      String evidenceSnapshotId,
      List<String> evidenceIds,
      EvidenceStatus evidenceStatus,
      int budgetSeconds
  ) {
    public PlannedQuestion {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }
}
