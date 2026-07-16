package com.linrun.interview.common.evidence;

import java.util.List;
import java.util.Objects;

/** 面试准备与实时追问共享的证据输出契约。 */
public record EvidencePacket(
    String capabilityAtomKey,
    String query,
    EvidenceStatus status,
    List<EvidenceCandidate> candidates,
    List<String> conflicts,
    List<String> degradedReasons
) {

  public EvidencePacket {
    capabilityAtomKey = capabilityAtomKey == null ? "" : capabilityAtomKey.trim();
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query 不能为空");
    }
    query = query.trim();
    status = Objects.requireNonNull(status, "status");
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
    if (status == EvidenceStatus.NONE && !candidates.isEmpty()) {
      throw new IllegalArgumentException("NONE 状态不能包含候选证据");
    }
    if (status == EvidenceStatus.CONFLICT && conflicts.isEmpty()) {
      throw new IllegalArgumentException("CONFLICT 状态必须说明冲突");
    }
  }

  public List<EvidenceRef> evidenceRefs() {
    return candidates.stream().map(EvidenceCandidate::ref).toList();
  }
}
