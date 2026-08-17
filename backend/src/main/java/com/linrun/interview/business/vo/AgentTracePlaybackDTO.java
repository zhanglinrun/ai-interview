package com.linrun.interview.business.vo;

import java.util.List;

/**
 * 一场面试的 Agent 轨迹回放：结构化决策，而不是步骤日志墙。
 */
public record AgentTracePlaybackDTO(
    String sessionId,
    List<String> sourceIds,
    boolean agentMode,
    int stepCount,
    int reflexionRounds,
    int criticRejects,
    int groundingRejects,
    int toolCalls,
    String emptyReason,
    String emptyHint,
    InterviewPlan plan,
    List<AgentTraceActDTO> acts,
    List<AgentTraceSpanDTO> spans
) {
  public AgentTracePlaybackDTO {
    spans = spans == null ? List.of() : List.copyOf(spans);
  }

  public AgentTracePlaybackDTO(
      String sessionId,
      List<String> sourceIds,
      boolean agentMode,
      int stepCount,
      int reflexionRounds,
      int criticRejects,
      int groundingRejects,
      int toolCalls,
      String emptyReason,
      String emptyHint,
      InterviewPlan plan,
      List<AgentTraceActDTO> acts
  ) {
    this(sessionId, sourceIds, agentMode, stepCount, reflexionRounds,
        criticRejects, groundingRejects, toolCalls, emptyReason, emptyHint, plan, acts, List.of());
  }

  public AgentTracePlaybackDTO withSpans(List<AgentTraceSpanDTO> next) {
    return new AgentTracePlaybackDTO(
        sessionId, sourceIds, agentMode, stepCount, reflexionRounds,
        criticRejects, groundingRejects, toolCalls, emptyReason, emptyHint, plan, acts, next);
  }
}
