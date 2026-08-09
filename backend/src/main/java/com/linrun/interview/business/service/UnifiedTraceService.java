package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.business.entity.AgentRunEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.AgentToolRunEntity;
import com.linrun.interview.business.entity.LlmUsageRecordEntity;
import com.linrun.interview.business.mapper.AgentRunMapper;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.business.mapper.AgentToolRunMapper;
import com.linrun.interview.business.mapper.LlmUsageRecordMapper;
import com.linrun.interview.business.vo.UnifiedTraceDTO;
import com.linrun.interview.business.vo.UnifiedTraceDTO.AgentRunView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.AgentStepView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.LlmUsageView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.RagRunView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.RagStageView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.TimelineEvent;
import com.linrun.interview.business.vo.UnifiedTraceDTO.ToolRunView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.ToolStatsView;
import com.linrun.interview.business.vo.UnifiedTraceDTO.ToolStatusStats;
import com.linrun.interview.rag.mapper.RagTraceCandidateMapper;
import com.linrun.interview.rag.mapper.RagTraceRunMapper;
import com.linrun.interview.rag.mapper.RagTraceStageMapper;
import com.linrun.interview.rag.model.RagTraceCandidateEntity;
import com.linrun.interview.rag.model.RagTraceRunEntity;
import com.linrun.interview.rag.model.RagTraceStageEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** User-isolated aggregation for the unified trace and session timeline APIs. */
@Service
@RequiredArgsConstructor
public class UnifiedTraceService {
  private static final int MAX_ITEMS = 200;
  private static final int MAX_OFFSET = 100_000;

  private final AgentRunMapper agentRunMapper;
  private final AgentRunStepMapper agentRunStepMapper;
  private final AgentToolRunMapper toolRunMapper;
  private final LlmUsageRecordMapper llmUsageMapper;
  private final RagTraceRunMapper ragRunMapper;
  private final RagTraceStageMapper ragStageMapper;
  private final RagTraceCandidateMapper ragCandidateMapper;

  public UnifiedTraceDTO get(String traceId, Long userId, int limit) {
    return get(traceId, userId, limit, 0);
  }

  public UnifiedTraceDTO get(String traceId, Long userId, int limit, int offset) {
    int safeLimit = safeLimit(limit);
    int safeOffset = safeOffset(offset);
    List<AgentRunEntity> runs = agentRunMapper.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
        .eq(AgentRunEntity::getTraceId, traceId).eq(AgentRunEntity::getUserId, userId)
        .orderByAsc(AgentRunEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    List<RagTraceRunEntity> ragRuns = ragRunMapper.selectList(Wrappers.<RagTraceRunEntity>lambdaQuery()
        .eq(RagTraceRunEntity::getTraceId, traceId).eq(RagTraceRunEntity::getUserId, userId)
        .orderByAsc(RagTraceRunEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    List<AgentToolRunEntity> tools = toolRunMapper.selectList(Wrappers.<AgentToolRunEntity>lambdaQuery()
        .eq(AgentToolRunEntity::getTraceId, traceId).eq(AgentToolRunEntity::getUserId, userId)
        .orderByAsc(AgentToolRunEntity::getStartedAt).last(limitClause(safeOffset, safeLimit)));
    List<LlmUsageRecordEntity> usage = llmUsageMapper.selectList(Wrappers.<LlmUsageRecordEntity>lambdaQuery()
        .eq(LlmUsageRecordEntity::getTraceId, traceId).eq(LlmUsageRecordEntity::getUserId, userId)
        .orderByAsc(LlmUsageRecordEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    return assemble(traceId, firstSession(runs, ragRuns, tools, usage),
        runs, ragRuns, tools, usage, safeLimit);
  }

  public UnifiedTraceDTO timeline(String sessionId, Long userId, int limit) {
    return timeline(sessionId, userId, limit, 0);
  }

  public UnifiedTraceDTO timeline(String sessionId, Long userId, int limit, int offset) {
    int safeLimit = safeLimit(limit);
    int safeOffset = safeOffset(offset);
    List<AgentRunEntity> runs = agentRunMapper.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
        .eq(AgentRunEntity::getSessionId, sessionId).eq(AgentRunEntity::getUserId, userId)
        .orderByAsc(AgentRunEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    List<RagTraceRunEntity> ragRuns = ragRunMapper.selectList(Wrappers.<RagTraceRunEntity>lambdaQuery()
        .eq(RagTraceRunEntity::getSessionId, sessionId).eq(RagTraceRunEntity::getUserId, userId)
        .orderByAsc(RagTraceRunEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    List<AgentToolRunEntity> tools = toolRunMapper.selectList(Wrappers.<AgentToolRunEntity>lambdaQuery()
        .eq(AgentToolRunEntity::getSessionId, sessionId).eq(AgentToolRunEntity::getUserId, userId)
        .orderByAsc(AgentToolRunEntity::getStartedAt).last(limitClause(safeOffset, safeLimit)));
    List<LlmUsageRecordEntity> usage = llmUsageMapper.selectList(Wrappers.<LlmUsageRecordEntity>lambdaQuery()
        .eq(LlmUsageRecordEntity::getSessionId, sessionId).eq(LlmUsageRecordEntity::getUserId, userId)
        .orderByAsc(LlmUsageRecordEntity::getCreatedAt).last(limitClause(safeOffset, safeLimit)));
    return assemble(null, sessionId, runs, ragRuns, tools, usage, safeLimit);
  }

  public ToolStatsView toolStats() {
    List<AgentToolRunEntity> rows = toolRunMapper.selectList(Wrappers.<AgentToolRunEntity>lambdaQuery()
        .orderByDesc(AgentToolRunEntity::getStartedAt).last("LIMIT 10000"));
    long total = rows.size();
    long hits = rows.stream().filter(row -> Boolean.TRUE.equals(row.getCacheHit())).count();
    long degraded = rows.stream().filter(row -> "DEGRADED".equals(row.getStatus())).count();
    double avg = average(rows.stream().map(AgentToolRunEntity::getLatencyMs).toList());
    Map<String, ToolStatusStats> byTool = rows.stream().collect(Collectors.groupingBy(
        AgentToolRunEntity::getToolName, LinkedHashMap::new,
        Collectors.collectingAndThen(Collectors.toList(), this::statusStats)));
    return new ToolStatsView(total, hits, degraded, rate(hits, total), avg, byTool);
  }

  private UnifiedTraceDTO assemble(String traceId, String sessionId,
                                   List<AgentRunEntity> runs,
                                   List<RagTraceRunEntity> ragRuns,
                                   List<AgentToolRunEntity> tools,
                                   List<LlmUsageRecordEntity> usage, int limit) {
    List<AgentRunView> runViews = runs.stream().map(run -> {
      List<AgentRunStepEntity> steps = agentRunStepMapper.selectList(
          Wrappers.<AgentRunStepEntity>lambdaQuery().eq(AgentRunStepEntity::getRunId, run.getRunId())
              .eq(AgentRunStepEntity::getUserId, run.getUserId())
              .orderByAsc(AgentRunStepEntity::getCreatedAt).last("LIMIT " + limit));
      return new AgentRunView(run.getRunId(), run.getCommandId(), run.getOperation(),
          run.getStatus(), run.getLatencyMs(), run.getDegradedReason(), run.getCreatedAt(),
          run.getCompletedAt(), steps.stream().map(this::stepView).toList());
    }).toList();
    List<RagRunView> ragViews = ragRuns.stream().map(run -> ragView(run, limit)).toList();
    List<ToolRunView> toolViews = tools.stream().map(this::toolView).toList();
    List<LlmUsageView> usageViews = usage.stream().map(this::usageView).toList();
    List<TimelineEvent> timeline = timeline(runViews, ragViews, toolViews, usageViews, limit);
    String operation = runViews.isEmpty() ? null : runViews.getFirst().operation();
    return new UnifiedTraceDTO(traceId, sessionId, operation, runViews, ragViews,
        toolViews, usageViews, timeline);
  }

  private AgentStepView stepView(AgentRunStepEntity step) {
    return new AgentStepView(step.getSpanId(), step.getParentSpanId(), step.getRole(),
        step.getAction(), step.getStatus(), step.getLatencyMs(), step.getStepOrder(),
        step.getObservation(), step.getCreatedAt());
  }

  private RagRunView ragView(RagTraceRunEntity run, int limit) {
    List<RagTraceStageEntity> stages = ragStageMapper.selectList(
        Wrappers.<RagTraceStageEntity>lambdaQuery().eq(RagTraceStageEntity::getRagRunId, run.getRagRunId())
            .orderByAsc(RagTraceStageEntity::getStartedAt).last("LIMIT " + limit));
    List<RagTraceCandidateEntity> candidates = ragCandidateMapper.selectList(
        Wrappers.<RagTraceCandidateEntity>lambdaQuery().eq(RagTraceCandidateEntity::getRagRunId, run.getRagRunId())
            .orderByAsc(RagTraceCandidateEntity::getRankNo).last("LIMIT " + limit));
    return new RagRunView(run.getRagRunId(), run.getAgentRunId(), run.getStatus(), run.getLatencyMs(),
        run.getDegradedReason(), run.getQuestion(), run.getAnswerSummary(), run.getCreatedAt(),
        stages.stream().map(stage -> new RagStageView(stage.getStage(), stage.getStatus(),
            stage.getDataSource(), stage.getInputSummary(), stage.getOutputSummary(),
            stage.getFallbackReason(), stage.getLatencyMs(), stage.getStartedAt(),
            stage.getCompletedAt())).toList(),
        candidates.stream().map(RagTraceCandidateEntity::getEvidenceId)
            .filter(Objects::nonNull).distinct().toList());
  }

  private ToolRunView toolView(AgentToolRunEntity row) {
    return new ToolRunView(row.getToolRunId(), row.getToolName(), row.getStatus(), row.getAgentRunId(),
        row.getRagRunId(), row.getCacheHit(), row.getRetryCount(), row.getLatencyMs(),
        row.getOutputSummary(), row.getFallbackReason(), row.getErrorCode(), row.getStartedAt(),
        row.getCompletedAt());
  }

  private LlmUsageView usageView(LlmUsageRecordEntity row) {
    return new LlmUsageView(row.getUsageId(), row.getOperation(), row.getProvider(), row.getModel(),
        row.getStatus() == null ? null : row.getStatus().name(), row.getLatencyMs(),
        row.getInputTokens(), row.getOutputTokens(), row.getTotalTokens(), row.getRetryCount(),
        row.getDegradedReason(), row.getAgentRunId(), row.getRagRunId(), row.getSpanId(),
        row.getCreatedAt());
  }

  private List<TimelineEvent> timeline(List<AgentRunView> runs, List<RagRunView> ragRuns,
                                       List<ToolRunView> tools, List<LlmUsageView> usage, int limit) {
    List<TimelineEvent> events = new ArrayList<>();
    runs.forEach(run -> events.add(new TimelineEvent("AGENT_RUN", run.agentRunId(), run.status(),
        run.latencyMs(), run.createdAt(), Map.of("operation", String.valueOf(run.operation())))));
    ragRuns.forEach(run -> events.add(new TimelineEvent("RAG_RUN", run.ragRunId(), run.status(),
        run.latencyMs(), run.createdAt(), Map.of("agentRunId", String.valueOf(run.agentRunId())))));
    tools.forEach(tool -> events.add(new TimelineEvent("TOOL", tool.toolRunId(), tool.status(),
        tool.latencyMs(), tool.startedAt(), Map.of("tool", String.valueOf(tool.toolName())))));
    usage.forEach(item -> events.add(new TimelineEvent("LLM", item.usageId(), item.status(),
        item.latencyMs(), item.createdAt(), Map.of("operation", String.valueOf(item.operation())))));
    return events.stream().sorted(Comparator.comparing(TimelineEvent::at,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(limit).toList();
  }

  private String firstSession(List<AgentRunEntity> runs, List<RagTraceRunEntity> ragRuns,
                             List<AgentToolRunEntity> tools, List<LlmUsageRecordEntity> usage) {
    if (!runs.isEmpty()) return runs.getFirst().getSessionId();
    if (!ragRuns.isEmpty()) return ragRuns.getFirst().getSessionId();
    if (!tools.isEmpty()) return tools.getFirst().getSessionId();
    return usage.isEmpty() ? null : usage.getFirst().getSessionId();
  }

  private int safeLimit(int limit) {
    return Math.min(Math.max(limit, 1), MAX_ITEMS);
  }

  private int safeOffset(int offset) {
    return Math.min(Math.max(offset, 0), MAX_OFFSET);
  }

  private String limitClause(int offset, int limit) {
    return "LIMIT " + offset + "," + limit;
  }

  private double average(List<Long> values) {
    return values.stream().filter(Objects::nonNull).mapToLong(Long::longValue).average().orElse(0.0);
  }

  private double rate(long numerator, long denominator) {
    return denominator == 0 ? 0.0 : (double) numerator / denominator;
  }

  private ToolStatusStats statusStats(List<AgentToolRunEntity> rows) {
    long total = rows.size();
    long success = count(rows, "SUCCESS");
    long empty = count(rows, "EMPTY");
    long degraded = count(rows, "DEGRADED");
    long timeout = count(rows, "TIMEOUT");
    long rejected = count(rows, "REJECTED");
    long failed = count(rows, "FAILED");
    long open = count(rows, "CIRCUIT_OPEN");
    long hits = rows.stream().filter(row -> Boolean.TRUE.equals(row.getCacheHit())).count();
    return new ToolStatusStats(total, success, empty, degraded, timeout, rejected, failed,
        open, rate(hits, total), average(rows.stream().map(AgentToolRunEntity::getLatencyMs).toList()));
  }

  private long count(List<AgentToolRunEntity> rows, String status) {
    return rows.stream().filter(row -> status.equals(row.getStatus())).count();
  }
}
