package com.linrun.interview.modules.knowledgebase.model;

import java.time.LocalDateTime;

public record RagQueryTraceDTO(
    String traceId,
    String question,
    String rewrittenQuestion,
    String decomposedQueriesJson,
    String cragGrade,
    String cragAction,
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
            entity.getQuestion(),
            entity.getRewrittenQuestion(),
            entity.getDecomposedQueriesJson(),
            entity.getCragGrade(),
            entity.getCragAction(),
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
