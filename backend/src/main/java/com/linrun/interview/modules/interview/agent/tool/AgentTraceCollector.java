package com.linrun.interview.modules.interview.agent.tool;

import com.linrun.interview.modules.interview.agent.model.AgentTraceStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前请求的 Agent 决策轨迹收集器。
 *
 * <p>工具执行监听器是共享单例（挂在缓存的 AiServices 实例上），轨迹经 ThreadLocal
 * 路由回本次编排。生命周期由 {@code InterviewOrchestrator} 统一管理：编排开始时
 * {@link #start()}，结束时 {@link #clear()}，与 {@link AgentContextHolder} 同一假设
 * （@Tool 在调用线程同步执行）。
 */
public final class AgentTraceCollector {

  private static final ThreadLocal<List<AgentTraceStep>> HOLDER = new ThreadLocal<>();

  private AgentTraceCollector() {
  }

  public static void start() {
    HOLDER.set(new ArrayList<>());
  }

  /** 当前线程的轨迹列表；未 start 时返回 null（监听器据此跳过收集）。 */
  public static List<AgentTraceStep> current() {
    return HOLDER.get();
  }

  public static void append(String role, String action, String actionInput, String observation) {
    List<AgentTraceStep> trace = HOLDER.get();
    if (trace == null) {
      return;
    }
    trace.add(new AgentTraceStep(trace.size() + 1, role, action,
        actionInput == null ? "" : actionInput,
        observation == null ? "" : observation));
  }

  public static void clear() {
    HOLDER.remove();
  }
}
