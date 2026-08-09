package com.linrun.interview.rag.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record EvalRunSummary(
    String runId,
    String title,
    String baselineKey,
    boolean baseline,
    double overallScore,
    boolean regression,
    EvalRunResponse.QualityGate qualityGate,
    LocalDateTime createdAt
) {
    public static EvalRunSummary from(EvalRunEntity entity) {
        return from(entity, null);
    }

    public static EvalRunSummary from(EvalRunEntity entity, ObjectMapper objectMapper) {
        EvalRunResponse.QualityGate gate = new EvalRunResponse.QualityGate(
            false, Map.of(), Map.of(), List.of());
        if (objectMapper != null && entity.getResponseJson() != null) {
            try {
                gate = objectMapper.readTree(entity.getResponseJson())
                    .path("qualityGate").traverse(objectMapper)
                    .readValueAs(EvalRunResponse.QualityGate.class);
            } catch (Exception ignored) {
                // A summary remains useful even if a legacy/corrupt snapshot
                // does not contain the optional quality-gate object.
            }
        }
        return new EvalRunSummary(
            entity.getRunId(), entity.getTitle(), entity.getBaselineKey(),
            Boolean.TRUE.equals(entity.getBaseline()),
            entity.getOverallScore() == null ? 0.0 : entity.getOverallScore(),
            Boolean.TRUE.equals(entity.getRegression()), gate, entity.getCreatedAt());
    }
}
