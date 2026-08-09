package com.linrun.interview.business.service;

import java.time.LocalDateTime;

/** Handle returned by the AgentRun lifecycle service. */
public record AgentRunHandle(
    String runId,
    String traceId,
    String commandId,
    String sessionId,
    Long userId,
    String operation,
    String rootSpanId,
    LocalDateTime startedAt
) {
}
