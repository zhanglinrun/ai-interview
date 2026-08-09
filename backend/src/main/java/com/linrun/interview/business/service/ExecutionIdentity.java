package com.linrun.interview.business.service;

/** Immutable business identity passed through Agent, RAG and Tool boundaries. */
public record ExecutionIdentity(
    Long userId,
    String sessionId,
    String traceId,
    String commandId,
    String agentRunId,
    Integer questionIndex
) {
  public ExecutionIdentity {
    if (sessionId != null && sessionId.isBlank()) {
      sessionId = null;
    }
    if (traceId != null && traceId.isBlank()) {
      traceId = null;
    }
    if (commandId != null && commandId.isBlank()) {
      commandId = null;
    }
    if (agentRunId != null && agentRunId.isBlank()) {
      agentRunId = null;
    }
  }

  public ExecutionIdentity withAgentRunId(String runId) {
    return new ExecutionIdentity(userId, sessionId, traceId, commandId, runId, questionIndex);
  }
}
