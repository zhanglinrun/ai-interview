package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

/** 流式回答完成后的引用校验元数据。 */
public record RagCitationMetadata(
    List<RagSourceDTO> sources,
    Double confidence,
    List<Integer> invalidCitations
) {
}
