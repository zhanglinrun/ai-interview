package com.linrun.interview.modules.knowledgebase.model;

import java.time.LocalDateTime;

public record RagQueryTraceDTO(
    String traceId,
    String question,
    String rewrittenQuestion,
    String routeStrategy,
    String routeReasoning,
    String decomposedQueriesJson,
    String cragGrade,
    String cragAction,
    Boolean graphAttempted,
    Boolean graphHit,
    String graphResult,
    String retrievedJson,
    String rerankedJson,
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
            entity.getDecomposedQueriesJson(),
            entity.getCragGrade(),
            entity.getCragAction(),
            entity.getGraphAttempted(),
            entity.getGraphHit(),
            entity.getGraphResult(),
            entity.getRetrievedJson(),
            entity.getRerankedJson(),
            entity.getFinalSourcesJson(),
            entity.getAnswer(),
            entity.getConfidence(),
            entity.getInvalidCitationsJson(),
            entity.getLatencyMs(),
            entity.getCreatedAt());
    }
}
