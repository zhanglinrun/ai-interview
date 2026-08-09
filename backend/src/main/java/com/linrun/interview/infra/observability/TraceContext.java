package com.linrun.interview.infra.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Request-scoped trace identifier used to correlate HTTP responses and application logs. */
public final class TraceContext {

  public static final String MDC_TRACE_ID = "traceId";

  private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

  private TraceContext() {
  }

  public static void setTraceId(String traceId) {
    if (traceId == null || traceId.isBlank()) {
      TRACE_ID.remove();
      MDC.remove(MDC_TRACE_ID);
      return;
    }
    TRACE_ID.set(traceId);
    if (traceId != null) {
      MDC.put(MDC_TRACE_ID, traceId);
    }
  }

  /** Returns the current trace, creating a server-owned value when needed. */
  public static String currentOrCreate() {
    String current = getTraceId();
    if (TraceIdPolicy.isValid(current)) {
      return current;
    }
    String created = TraceIdPolicy.generate();
    setTraceId(created);
    return created;
  }

  public static String getTraceId() {
    return TRACE_ID.get();
  }

  /** Captures both the business trace and the MDC map for async propagation. */
  public static Snapshot snapshot() {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    return new Snapshot(getTraceId(), mdc == null ? Map.of() : Map.copyOf(mdc));
  }

  /** Restores a snapshot and returns a scope that restores the previous state. */
  public static Scope restore(Snapshot snapshot) {
    Snapshot previous = snapshot();
    apply(snapshot);
    return () -> apply(previous);
  }

  public static Runnable wrap(Runnable task) {
    Snapshot captured = snapshot();
    return () -> {
      try (Scope ignored = restore(captured)) {
        task.run();
      }
    };
  }

  public static <T> Callable<T> wrap(Callable<T> task) {
    Snapshot captured = snapshot();
    return () -> {
      try (Scope ignored = restore(captured)) {
        return task.call();
      }
    };
  }

  public static <T> Supplier<T> wrap(Supplier<T> task) {
    Snapshot captured = snapshot();
    return () -> {
      try (Scope ignored = restore(captured)) {
        return task.get();
      }
    };
  }

  public static void clear() {
    TRACE_ID.remove();
    MDC.remove(MDC_TRACE_ID);
  }

  private static void apply(Snapshot snapshot) {
    TRACE_ID.remove();
    MDC.clear();
    if (snapshot == null) {
      return;
    }
    if (snapshot.traceId() != null) {
      TRACE_ID.set(snapshot.traceId());
    }
    if (!snapshot.mdc().isEmpty()) {
      MDC.setContextMap(snapshot.mdc());
    }
  }

  public record Snapshot(String traceId, Map<String, String> mdc) {
    public Snapshot {
      mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
