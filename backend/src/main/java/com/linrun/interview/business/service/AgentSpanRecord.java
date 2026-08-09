package com.linrun.interview.business.service;

/** A redacted span appended to an AgentRun. */
public record AgentSpanRecord(
    String spanId,
    String parentSpanId,
    String role,
    String action,
    String actionInput,
    String observation,
    String status,
    Long latencyMs,
    String metadataJson,
    Integer stepOrder
) {
  public AgentSpanRecord {
    status = status == null || status.isBlank() ? "COMPLETED" : status;
    stepOrder = stepOrder == null ? 0 : stepOrder;
  }
}
