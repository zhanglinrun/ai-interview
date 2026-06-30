package com.linrun.interview.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RagEvalRequest(
    @NotEmpty List<Long> knowledgeBaseIds,
    @NotEmpty List<Item> items,
    Integer k,
    String title
) {
    public RagEvalRequest(List<Long> knowledgeBaseIds, List<Item> items, Integer k) {
        this(knowledgeBaseIds, items, k, null);
    }

    public record Item(
        String question,
        List<String> expectedKeywords,
        List<String> expectedChunkIds
    ) {}
}
