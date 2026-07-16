package com.linrun.interview.common.evidence;

import java.util.Objects;

/** 进入面试证据包的最小片段；text 必须是受限长度的快照，而不是整份源文档。 */
public record EvidenceCandidate(
    EvidenceRef ref,
    String text,
    Double retrievalScore,
    Double rerankScore,
    double domainWeight,
    int rank
) {

  public EvidenceCandidate {
    ref = Objects.requireNonNull(ref, "ref");
    text = text == null ? "" : text;
    if (rank <= 0) {
      throw new IllegalArgumentException("rank 必须大于 0");
    }
    if (!Double.isFinite(domainWeight) || domainWeight <= 0.0d) {
      throw new IllegalArgumentException("domainWeight 必须大于 0");
    }
  }
}
