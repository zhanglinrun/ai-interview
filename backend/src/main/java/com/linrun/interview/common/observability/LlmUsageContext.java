package com.linrun.interview.common.observability;

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
    Context previous = HOLDER.get();
    HOLDER.set(new Context(
        userId, sessionId, reportId, operation, provider, degradedReason, new AtomicInteger()));
    return () -> restore(previous);
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
      AtomicInteger attemptCounter
  ) {
    public int nextRetryCount() {
      return Math.max(0, attemptCounter.getAndIncrement());
    }
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
