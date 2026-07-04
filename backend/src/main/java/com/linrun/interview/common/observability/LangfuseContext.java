package com.linrun.interview.common.observability;

import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单请求/单线程的追踪上下文（P5）。
 *
 * <p>持有当前 traceId 与「活跃观测（span）栈」，供父子 span 链接与
 * {@link LangfuseChatModelListener} 把 LLM generation 挂到当前活跃 span 之下。
 * 同时把 traceId 写入 {@link MDC}（key {@value #MDC_TRACE_ID}），实现日志-trace 关联。
 *
 * <p>基于 {@link ThreadLocal}，不跨线程传播；并行虚拟线程内的调用会各自成新 trace 或无 parent，
 * 这是已知取舍（观测是旁路，不为其牺牲主链路简洁性）。
 */
public final class LangfuseContext {

  public static final String MDC_TRACE_ID = "traceId";

  private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

  private LangfuseContext() {
  }

  private static final class State {
    String traceId;
    boolean traceEmitted;
    final Deque<String> observationStack = new ArrayDeque<>();
  }

  private static State state() {
    State s = HOLDER.get();
    if (s == null) {
      s = new State();
      HOLDER.set(s);
    }
    return s;
  }

  /** 设置当前 traceId（同时写入 MDC）。 */
  public static void setTraceId(String traceId) {
    state().traceId = traceId;
    if (traceId != null) {
      MDC.put(MDC_TRACE_ID, traceId);
    }
  }

  public static String getTraceId() {
    State s = HOLDER.get();
    return s == null ? null : s.traceId;
  }

  public static boolean hasTrace() {
    return getTraceId() != null;
  }

  /** 标记 trace-create 事件已上报（保证一个 trace 只创建一次）。 */
  public static void markTraceEmitted() {
    state().traceEmitted = true;
  }

  public static boolean isTraceEmitted() {
    State s = HOLDER.get();
    return s != null && s.traceEmitted;
  }

  /** 压入一个活跃观测 id（span 开始时调用）。 */
  public static void pushObservation(String observationId) {
    state().observationStack.push(observationId);
  }

  /** 弹出栈顶观测 id（span 结束时调用）。 */
  public static void popObservation() {
    State s = HOLDER.get();
    if (s != null && !s.observationStack.isEmpty()) {
      s.observationStack.pop();
    }
  }

  /** 当前活跃观测 id（作为新 span/generation 的 parent），无则 null。 */
  public static String currentParentId() {
    State s = HOLDER.get();
    return (s == null || s.observationStack.isEmpty()) ? null : s.observationStack.peek();
  }

  /** 清理全部上下文（请求结束时调用），同时清 MDC。 */
  public static void clear() {
    HOLDER.remove();
    MDC.remove(MDC_TRACE_ID);
  }
}
