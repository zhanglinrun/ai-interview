package com.linrun.interview.business.service;

/** Structured tool outcome; data is never implicitly persisted or logged in full. */
public record ToolResult<T>(
    ToolStatus status,
    T data,
    String summary,
    String errorCode,
    String degradedReason,
    long latencyMs,
    boolean cacheHit,
    int retryCount,
    String ragRunId
) {
  public static <T> ToolResult<T> success(T data, String summary) {
    return new ToolResult<>(ToolStatus.SUCCESS, data, summary, null, null, 0L, false, 0, null);
  }

  public static <T> ToolResult<T> empty(T data, String summary) {
    return new ToolResult<>(ToolStatus.EMPTY, data, summary, null, null, 0L, false, 0, null);
  }

  public static <T> ToolResult<T> degraded(T data, String reason, String summary) {
    return new ToolResult<>(ToolStatus.DEGRADED, data, summary, null, reason, 0L, false, 0, null);
  }

  public static <T> ToolResult<T> circuitOpen(String summary) {
    return new ToolResult<>(ToolStatus.CIRCUIT_OPEN, null, summary,
        "TOOL_CIRCUIT_OPEN", "circuit_open", 0L, false, 0, null);
  }

  public static <T> ToolResult<T> rejected(String code, String reason) {
    return new ToolResult<>(ToolStatus.REJECTED, null, reason, code, reason, 0L, false, 0, null);
  }

  public ToolResult<T> measured(long latency, boolean hit, int retries) {
    return new ToolResult<>(status, data, summary, errorCode, degradedReason,
        latency, hit, retries, ragRunId);
  }

  public ToolResult<T> withRagRunId(String id) {
    return new ToolResult<>(status, data, summary, errorCode, degradedReason,
        latencyMs, cacheHit, retryCount, id);
  }
}
