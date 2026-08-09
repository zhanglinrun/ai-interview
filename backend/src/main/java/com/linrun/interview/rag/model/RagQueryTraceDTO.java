package com.linrun.interview.rag.model;

import java.time.LocalDateTime;

public record RagQueryTraceDTO(
    String traceId,
    String ragRunId,
    String question,
    String rewrittenQuestion,
    String decomposedQueriesJson,
    String cragGrade,
    String cragAction,
    String routeSource,
    String routeIntent,
    Double routeConfidence,
    String routeReasoning,
    String evidenceScopeJson,
    String evidenceStatus,
    String evidenceRefsJson,
    String degradedReasonsJson,
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
            entity.getRagRunId(),
            entity.getQuestion(),
            entity.getRewrittenQuestion(),
            entity.getDecomposedQueriesJson(),
            entity.getCragGrade(),
            entity.getCragAction(),
            entity.getRouteSource(),
            entity.getRouteIntent(),
            entity.getRouteConfidence(),
            entity.getRouteReasoning(),
            entity.getEvidenceScopeJson(),
            entity.getEvidenceStatus(),
            entity.getEvidenceRefsJson(),
            entity.getDegradedReasonsJson(),
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
