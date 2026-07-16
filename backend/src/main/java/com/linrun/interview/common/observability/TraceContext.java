package com.linrun.interview.common.observability;

import org.slf4j.MDC;

/** Request-scoped trace identifier used to correlate HTTP responses and application logs. */
public final class TraceContext {

  public static final String MDC_TRACE_ID = "traceId";

  private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

  private TraceContext() {
  }

  public static void setTraceId(String traceId) {
    TRACE_ID.set(traceId);
    if (traceId != null) {
      MDC.put(MDC_TRACE_ID, traceId);
    }
  }

  public static String getTraceId() {
    return TRACE_ID.get();
  }

  public static void clear() {
    TRACE_ID.remove();
    MDC.remove(MDC_TRACE_ID);
  }
}
