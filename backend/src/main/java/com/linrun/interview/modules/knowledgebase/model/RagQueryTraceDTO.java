package com.linrun.interview.modules.knowledgebase.model;

import java.time.LocalDateTime;

public record RagQueryTraceDTO(
    String traceId,
    String question,
    String rewrittenQuestion,
    String routeStrategy,
    String routeReasoning,
    String retrievedJson,
    String finalSourcesJson,
    String answer,
    Double confidence,
    String invalidCitationsJson,
    Long latencyMs,
    LocalDateTime createdAt
) {
    public static RagQueryTraceDTO from(RagQueryTraceEntity entity) {
        return new RagQueryTraceDTO(
            entity.getTraceId(),
            entity.getQuestion(),
            entity.getRewrittenQuestion(),
            entity.getRouteStrategy(),
            entity.getRouteReasoning(),
            entity.getRetrievedJson(),
            entity.getFinalSourcesJson(),
            entity.getAnswer(),
            entity.getConfidence(),
            entity.getInvalidCitationsJson(),
            entity.getLatencyMs(),
            entity.getCreatedAt());
    }
}
