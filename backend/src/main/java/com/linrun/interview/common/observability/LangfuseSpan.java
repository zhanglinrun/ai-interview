package com.linrun.interview.common.observability;

import java.time.Instant;

/**
 * 一次观测（span 或 generation）的句柄（P5）。
 *
 * <p>由 {@link LangfuseTracer#span}/{@link LangfuseTracer#generation} 产出，
 * 结束时回传给 {@code tracer.end(...)} 上报。{@link #NOOP} 用于 tracer 关闭或无活跃 trace 时，
 * 让埋点代码无需判空、无副作用。
 */
public final class LangfuseSpan {

  /** 关闭/无 trace 时的空句柄，所有 end 操作对它无效。 */
  public static final LangfuseSpan NOOP = new LangfuseSpan(true);

  final boolean noop;
  final String id;
  final String traceId;
  final String parentId;
  final boolean generation;
  final String name;
  final String model;
  final String inputJson;
  final Instant startTime;

  private LangfuseSpan(boolean noop) {
    this.noop = noop;
    this.id = null;
    this.traceId = null;
    this.parentId = null;
    this.generation = false;
    this.name = null;
    this.model = null;
    this.inputJson = null;
    this.startTime = null;
  }

  LangfuseSpan(String id, String traceId, String parentId, boolean generation,
               String name, String model, String inputJson) {
    this.noop = false;
    this.id = id;
    this.traceId = traceId;
    this.parentId = parentId;
    this.generation = generation;
    this.name = name;
    this.model = model;
    this.inputJson = inputJson;
    this.startTime = Instant.now();
  }

  public boolean isNoop() {
    return noop;
  }

  public String id() {
    return id;
  }
}
