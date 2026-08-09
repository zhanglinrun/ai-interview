package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.AgentTraceStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 Agent 编排的显式上下文。
 *
 * <p>上下文随编排调用传递给工具和监听器，不依赖全局 ThreadLocal，因此异步/并发
 * 面试不会把一个候选人的简历或 trace 串到另一个请求。</p>
 */
public final class AgentExecutionContext {

    private final String sessionId;
    private final Long userId;
    private final AgentToolContext toolContext;
    private final ExecutionIdentity identity;
    private final List<AgentTraceStep> steps = new ArrayList<>();

    private AgentExecutionContext(String sessionId, Long userId, AgentToolContext toolContext,
                                  ExecutionIdentity identity) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.toolContext = toolContext;
        this.identity = identity;
    }

    public static AgentExecutionContext open(String sessionId, Long userId,
                                             AgentToolContext toolContext) {
        return new AgentExecutionContext(sessionId, userId, toolContext, null);
    }

    public static AgentExecutionContext open(String sessionId, Long userId,
                                             AgentToolContext toolContext,
                                             ExecutionIdentity identity) {
        return new AgentExecutionContext(sessionId, userId, toolContext, identity);
    }

    public String sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public AgentToolContext toolContext() {
        return toolContext;
    }

    public ExecutionIdentity identity() {
        return identity;
    }

    public synchronized void append(String role, String action, String input, String observation) {
        steps.add(new AgentTraceStep(steps.size() + 1, role, action,
            input == null ? "" : input, observation == null ? "" : observation));
    }

    public synchronized List<AgentTraceStep> steps() {
        return List.copyOf(steps);
    }
}
