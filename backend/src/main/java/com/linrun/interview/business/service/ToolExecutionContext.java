package com.linrun.interview.business.service;

public record ToolExecutionContext(
    Long userId,
    String sessionId,
    String traceId,
    String agentRunId,
    String spanId,
    String role,
    Integer questionIndex
) {
  public ToolExecutionContext(
      Long userId,
      String sessionId,
      String traceId,
      String agentRunId,
      String spanId,
      String role) {
    this(userId, sessionId, traceId, agentRunId, spanId, role, null);
  }
}
