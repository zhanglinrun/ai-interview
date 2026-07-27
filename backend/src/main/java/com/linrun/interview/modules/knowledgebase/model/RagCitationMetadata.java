package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

/** 流式回答完成后的引用校验元数据（含知识库问答 grounded 闸门状态）。 */
public record RagCitationMetadata(
    List<RagSourceDTO> sources,
    Double confidence,
    List<Integer> invalidCitations,
    /** pass / grounded / need_escalate；仅知识库问答路径使用 */
    String groundedStatus
) {
  public RagCitationMetadata(List<RagSourceDTO> sources, Double confidence, List<Integer> invalidCitations) {
    this(sources, confidence, invalidCitations, null);
  }
}
