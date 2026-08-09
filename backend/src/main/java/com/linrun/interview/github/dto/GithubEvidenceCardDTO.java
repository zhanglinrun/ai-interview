package com.linrun.interview.github.dto;

import com.linrun.interview.rag.model.EvidenceRef;
import java.util.List;

/** 复盘可引用的中立项目深挖题，不依据 Commit 数量评分。 */
public record GithubEvidenceCardDTO(
    String atomId,
    String atomVersion,
    String capabilityName,
    String evidenceStatus,
    double confidence,
    String neutralNote,
    String interviewQuestion,
    List<EvidenceRef> evidenceRefs
) {
  public GithubEvidenceCardDTO {
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
  }
}
