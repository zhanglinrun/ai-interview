package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CapabilityState;
import java.time.LocalDateTime;
import java.util.List;

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
