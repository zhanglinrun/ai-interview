package com.linrun.interview.business.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Safe, user-scoped trace projection; it intentionally contains summaries, not raw prompts. */
public record UnifiedTraceDTO(
    String traceId,
    String sessionId,
    String operation,
    List<AgentRunView> agentRuns,
    List<RagRunView> ragRuns,
    List<ToolRunView> toolRuns,
    List<LlmUsageView> llmUsage,
    List<TimelineEvent> timeline
) {
  public UnifiedTraceDTO {
    agentRuns = agentRuns == null ? List.of() : List.copyOf(agentRuns);
    ragRuns = ragRuns == null ? List.of() : List.copyOf(ragRuns);
    toolRuns = toolRuns == null ? List.of() : List.copyOf(toolRuns);
    llmUsage = llmUsage == null ? List.of() : List.copyOf(llmUsage);
    timeline = timeline == null ? List.of() : List.copyOf(timeline);
  }

  public record AgentRunView(String agentRunId, String commandId, String operation,
                             String status, Long latencyMs, String degradedReason,
                             LocalDateTime createdAt, LocalDateTime completedAt,
                             List<AgentStepView> steps) {
    public AgentRunView {
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  public record AgentStepView(String spanId, String parentSpanId, String role,
                              String action, String status, Long latencyMs,
                              Integer stepOrder, String observation, LocalDateTime createdAt) {
  }

  public record RagRunView(String ragRunId, String agentRunId, String status,
                           Long latencyMs, String degradedReason, String question, String answerSummary,
                           LocalDateTime createdAt, List<RagStageView> stages,
                           List<String> evidenceIds) {
    public RagRunView(String ragRunId, String agentRunId, String status,
                      Long latencyMs, String question, String answerSummary,
                      LocalDateTime createdAt, List<RagStageView> stages,
                      List<String> evidenceIds) {
      this(ragRunId, agentRunId, status, latencyMs, null, question, answerSummary,
          createdAt, stages, evidenceIds);
    }

    public RagRunView {
      stages = stages == null ? List.of() : List.copyOf(stages);
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record RagStageView(String stage, String status, String dataSource,
                             String inputSummary, String outputSummary,
                             String fallbackReason, Long latencyMs,
                             LocalDateTime startedAt, LocalDateTime completedAt) {
  }

  public record ToolRunView(String toolRunId, String toolName, String status,
                            String agentRunId, String ragRunId, Boolean cacheHit,
                            Integer retryCount, Long latencyMs, String outputSummary,
                            String fallbackReason, String errorCode,
                            LocalDateTime startedAt, LocalDateTime completedAt) {
  }

  public record LlmUsageView(String usageId, String operation, String provider,
                             String model, String status, Long latencyMs,
                             Integer inputTokens, Integer outputTokens,
                             Integer totalTokens, Integer retryCount,
                             String degradedReason, String agentRunId,
                             String ragRunId, String spanId, LocalDateTime createdAt) {
  }

  public record TimelineEvent(String kind, String id, String status,
                              Long latencyMs, LocalDateTime at, Map<String, String> metadata) {
    public TimelineEvent {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  public record ToolStatsView(long total, long cacheHits, long degraded,
                              double cacheHitRate, double averageLatencyMs,
                              Map<String, ToolStatusStats> byTool) {
    public ToolStatsView {
      byTool = byTool == null ? Map.of() : Map.copyOf(byTool);
    }
  }

  public record ToolStatusStats(long total, long success, long empty, long degraded,
                                long timeout, long rejected, long failed,
                                long circuitOpen, double cacheHitRate,
                                double averageLatencyMs) {
  }
}
