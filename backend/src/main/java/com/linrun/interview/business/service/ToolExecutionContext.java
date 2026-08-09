package com.linrun.interview.business.service;

public record ToolExecutionContext(
    Long userId,
    String sessionId,
    String traceId,
    String agentRunId,
    String spanId,
    String role
) {
}
