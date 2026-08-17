package com.linrun.interview.business.vo;

import java.util.List;

/** 标准 agent-trace 节点：agent / chat / tool，可嵌套。 */
public record AgentTraceSpanDTO(
    String spanId,
    String parentSpanId,
    String kind,
    String role,
    String action,
    String title,
    String input,
    String output,
    String status,
    Long latencyMs,
    String model,
    Integer inputTokens,
    Integer outputTokens,
    Integer questionIndex,
    List<AgentTraceSpanDTO> children
) {
  public AgentTraceSpanDTO {
    children = children == null ? List.of() : List.copyOf(children);
  }

  public AgentTraceSpanDTO withChildren(List<AgentTraceSpanDTO> next) {
    return new AgentTraceSpanDTO(
        spanId, parentSpanId, kind, role, action, title, input, output, status,
        latencyMs, model, inputTokens, outputTokens, questionIndex, next);
  }
}
