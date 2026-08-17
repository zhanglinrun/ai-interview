package com.linrun.interview.infra.observability;

import java.util.concurrent.atomic.AtomicInteger;

/** 显式下传的 LLM 用量上下文；异步线程不得临时读取 UserContext。 */
public final class LlmUsageContext {

  private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

  private LlmUsageContext() {
  }

  public static Scope open(
      Long userId,
      String sessionId,
      String reportId,
      String operation,
      String provider,
      String degradedReason
  ) {
    return open(userId, sessionId, reportId, operation, provider, degradedReason,
        null, null, null);
  }

  public static Scope open(
      Long userId,
      String sessionId,
      String reportId,
      String operation,
      String provider,
      String degradedReason,
      String agentRunId,
      String ragRunId,
      String spanId
  ) {
    Context previous = HOLDER.get();
    HOLDER.set(new Context(
        userId, sessionId, reportId, operation, provider, degradedReason,
        agentRunId, ragRunId, spanId, null, new AtomicInteger(), null));
    return () -> restore(previous);
  }

  /** 只覆盖当前线程的 agent 角色，用于 Planner / Interviewer / Critic 分段。 */
  public static Scope overlayAgentRole(String agentRole) {
    Context current = HOLDER.get();
    if (current == null) {
      return () -> { };
    }
    HOLDER.set(current.withAgentRole(agentRole));
    return () -> restore(current);
  }

  /** 只覆盖当前线程的题号，供 chat / tool span 写入 agent_steps.questionIndex。 */
  public static Scope overlayQuestionIndex(Integer questionIndex) {
    Context current = HOLDER.get();
    if (current == null) {
      return () -> { };
    }
    HOLDER.set(current.withQuestionIndex(questionIndex));
    return () -> restore(current);
  }

  /** 把当前 span 切到子节点（例如 chat），结束时恢复。 */
  public static Scope overlaySpanId(String spanId) {
    Context current = HOLDER.get();
    if (current == null) {
      return () -> { };
    }
    HOLDER.set(current.withSpanId(spanId));
    return () -> restore(current);
  }

  public static Scope open(
      Long userId,
      String sessionId,
      String reportId,
      String operation
  ) {
    return open(userId, sessionId, reportId, operation, "BYOK", null);
  }

  /**
   * 为没有显式业务上下文的用户模型调用补充兜底计量上下文。
   *
   * <p>岗位逐题评价、报告等链路会先打开带 session/report 的细粒度上下文，兜底层不能覆盖它；
   * JD、简历、RAG 等普通 BYOK 调用未显式标注时，则至少保留 userId、耗时与 Token。
   */
  public static Scope openIfAbsent(Long userId, String operation) {
    if (HOLDER.get() != null) {
      return () -> { };
    }
    return open(userId, null, null, operation);
  }

  public static Context current() {
    return HOLDER.get();
  }

  public static void replace(Context next) {
    if (next == null) {
      HOLDER.remove();
    } else {
      HOLDER.set(next);
    }
  }

  private static void restore(Context previous) {
    if (previous == null) {
      HOLDER.remove();
    } else {
      HOLDER.set(previous);
    }
  }

  public record Context(
      Long userId,
      String sessionId,
      String reportId,
      String operation,
      String provider,
      String degradedReason,
      String agentRunId,
      String ragRunId,
      String spanId,
      String agentRole,
      AtomicInteger attemptCounter,
      Integer questionIndex
  ) {
    public int nextRetryCount() {
      return Math.max(0, attemptCounter.getAndIncrement());
    }

    public Context withSpanId(String nextSpanId) {
      return new Context(userId, sessionId, reportId, operation, provider, degradedReason,
          agentRunId, ragRunId, nextSpanId, agentRole, attemptCounter, questionIndex);
    }

    public Context withAgentRole(String nextRole) {
      return new Context(userId, sessionId, reportId, operation, provider, degradedReason,
          agentRunId, ragRunId, spanId, nextRole, attemptCounter, questionIndex);
    }

    public Context withQuestionIndex(Integer nextQuestionIndex) {
      return new Context(userId, sessionId, reportId, operation, provider, degradedReason,
          agentRunId, ragRunId, spanId, agentRole, attemptCounter, nextQuestionIndex);
    }
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
