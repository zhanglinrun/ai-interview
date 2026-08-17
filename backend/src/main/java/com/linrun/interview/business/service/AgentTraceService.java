package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.business.entity.AgentRunEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.vo.AgentTraceStep;
import com.linrun.interview.business.mapper.AgentRunMapper;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.infra.observability.TraceContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 编排轨迹持久化：写入 agent_runs + agent_steps，供前端按会话回放决策过程。
 * 轨迹是观测数据，写入失败只告警不阻断出题主链路。
 */
@Slf4j
@Service
public class AgentTraceService extends ServiceImpl<AgentRunStepMapper, AgentRunStepEntity> {

  private static final int MAX_TEXT_LENGTH = 4000;

  private final AgentRunStepMapper agentRunStepMapper;
  private final AgentRunMapper agentRunMapper;

  public AgentTraceService(AgentRunStepMapper agentRunStepMapper) {
    this(agentRunStepMapper, null);
  }

  @Autowired
  public AgentTraceService(AgentRunStepMapper agentRunStepMapper, AgentRunMapper agentRunMapper) {
    this.agentRunStepMapper = agentRunStepMapper;
    this.agentRunMapper = agentRunMapper;
    this.baseMapper = agentRunStepMapper;
  }

  /**
   * 批量落库一次编排产生的轨迹步骤（失败静默降级）。
   */
  public void saveStepsQuietly(String sessionId, Long userId, Integer questionIndex,
                               List<AgentTraceStep> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    try {
      saveSteps(sessionId, userId, questionIndex, steps);
    } catch (Exception e) {
      log.warn("Agent 轨迹落库失败（不阻断主流程）: sessionId={}, questionIndex={}",
          sessionId, questionIndex, e);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveSteps(String sessionId, Long userId, Integer questionIndex,
                        List<AgentTraceStep> steps) {
    AgentRunHandle run = startOrGet(new ExecutionIdentity(
        userId, sessionId, null, null, null, questionIndex),
        questionIndex == null ? "planning" : "question");
    for (AgentTraceStep step : steps) {
      append(run, new AgentSpanRecord(
          "span-" + UUID.randomUUID(), null, step.role(), step.action(),
          step.actionInput(), step.observation(), "COMPLETED", null, null, step.step(),
          questionIndex));
    }
    finish(run, AgentRunStatus.COMPLETED, 0L, null, "steps=" + steps.size());
  }

  /** Idempotently creates or returns the run for a command and operation. */
  public AgentRunHandle startOrGet(ExecutionIdentity identity, String operation) {
    if (identity == null) {
      identity = new ExecutionIdentity(null, null, null, null, null, null);
    }
    String safeOperation = operation == null || operation.isBlank() ? "interview" : operation;
    if (agentRunMapper == null) {
      String runId = identity.agentRunId() == null ? "agent-" + UUID.randomUUID() : identity.agentRunId();
      return new AgentRunHandle(runId, identity.traceId(), identity.commandId(),
          identity.sessionId(), identity.userId(), safeOperation, "root-" + UUID.randomUUID(),
          LocalDateTime.now());
    }

    if (identity.agentRunId() != null) {
      var query = Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getRunId, identity.agentRunId());
      if (identity.userId() != null) {
        query.eq(AgentRunEntity::getUserId, identity.userId());
      }
      if (identity.sessionId() != null) {
        query.eq(AgentRunEntity::getSessionId, identity.sessionId());
      }
      AgentRunEntity existing = agentRunMapper.selectOne(query.last("LIMIT 1"));
      if (existing != null) {
        return toHandle(existing);
      }
    }

    if (identity.commandId() != null && identity.sessionId() != null) {
      AgentRunEntity existing = agentRunMapper.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getSessionId, identity.sessionId())
          .eq(AgentRunEntity::getUserId, identity.userId())
          .eq(AgentRunEntity::getCommandId, identity.commandId())
          .eq(AgentRunEntity::getOperation, safeOperation)
          .last("LIMIT 1"));
      if (existing != null) {
        return toHandle(existing);
      }
    }

    LocalDateTime now = LocalDateTime.now();
    String runId = identity.agentRunId() == null ? "agent-" + UUID.randomUUID() : identity.agentRunId();
    String rootSpanId = "root-" + UUID.randomUUID();
    AgentRunEntity entity = AgentRunEntity.builder()
        .runId(runId)
        .traceId(identity.traceId())
        .commandId(identity.commandId())
        .operation(safeOperation)
        .rootSpanId(rootSpanId)
        .userId(identity.userId())
        .sessionId(identity.sessionId())
        .questionIndex(identity.questionIndex())
        .status(AgentRunStatus.RUNNING.name())
        .createdAt(now)
        .build();
    try {
      agentRunMapper.insert(entity);
    } catch (Exception duplicate) {
      if (identity.commandId() != null && identity.sessionId() != null) {
        AgentRunEntity existing = agentRunMapper.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
            .eq(AgentRunEntity::getSessionId, identity.sessionId())
            .eq(AgentRunEntity::getUserId, identity.userId())
            .eq(AgentRunEntity::getCommandId, identity.commandId())
            .eq(AgentRunEntity::getOperation, safeOperation)
            .last("LIMIT 1"));
        if (existing != null) {
          return toHandle(existing);
        }
      }
      throw duplicate;
    }
    return toHandle(entity);
  }

  /**
   * Reuses the latest RUNNING run for this session+operation, or starts a new one.
   * Used by the evaluating phase so enqueue / LLM / complete share one AgentRun.
   */
  public AgentRunHandle startOrResumeSessionOperation(Long userId, String sessionId, String operation) {
    String safeOperation = operation == null || operation.isBlank() ? "interview" : operation;
    if (agentRunMapper != null && userId != null && sessionId != null && !sessionId.isBlank()) {
      AgentRunEntity existing = agentRunMapper.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getSessionId, sessionId)
          .eq(AgentRunEntity::getUserId, userId)
          .eq(AgentRunEntity::getOperation, safeOperation)
          .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
          .orderByDesc(AgentRunEntity::getCreatedAt)
          .last("LIMIT 1"));
      if (existing != null) {
        return toHandle(existing);
      }
    }
    return startOrGetQuietly(new ExecutionIdentity(
        userId, sessionId, TraceContext.getTraceId(),
        safeOperation + ":" + sessionId + ":" + UUID.randomUUID(), null, null), safeOperation);
  }

  public AgentRunHandle startOrGetQuietly(ExecutionIdentity identity, String operation) {
    try {
      return startOrGet(identity, operation);
    } catch (Exception e) {
      log.warn("AgentRun 创建失败，降级为内存句柄: sessionId={}, commandId={}, reason={}",
          identity == null ? null : identity.sessionId(),
          identity == null ? null : identity.commandId(), e.getMessage());
      ExecutionIdentity safe = identity == null
          ? new ExecutionIdentity(null, null, null, null, null, null) : identity;
      return new AgentRunHandle(
          safe.agentRunId() == null ? "agent-" + UUID.randomUUID() : safe.agentRunId(),
          safe.traceId(), safe.commandId(), safe.sessionId(), safe.userId(),
          operation == null ? "interview" : operation, "root-" + UUID.randomUUID(),
          LocalDateTime.now());
    }
  }

  public void append(AgentRunHandle run, AgentSpanRecord span) {
    if (run == null || span == null || agentRunStepMapper == null) {
      return;
    }
    AgentRunStepEntity entity = AgentRunStepEntity.builder()
        .runId(run.runId())
        .traceId(run.traceId())
        .spanId(span.spanId() == null ? "span-" + UUID.randomUUID() : span.spanId())
        .parentSpanId(span.parentSpanId())
        .userId(run.userId())
        .sessionId(run.sessionId())
        .role(truncate(span.role()))
        .stepOrder(span.stepOrder())
        .action(truncate(span.action()))
        .actionInput(truncate(span.actionInput()))
        .observation(truncate(span.observation()))
        .status(span.status())
        .latencyMs(span.latencyMs())
        .metadataJson(truncate(span.metadataJson()))
        .questionIndex(span.questionIndex())
        .createdAt(LocalDateTime.now())
        .build();
    agentRunStepMapper.insert(entity);
  }

  public void appendQuietly(AgentRunHandle run, AgentSpanRecord span) {
    try {
      append(run, span);
    } catch (Exception e) {
      log.warn("Agent span 追加失败: runId={}, reason={}", run == null ? null : run.runId(),
          e.getMessage());
    }
  }

  public void finish(AgentRunHandle run, AgentRunStatus status, long latencyMs,
                     String degradedReason, String outputSummary) {
    if (run == null || agentRunMapper == null) {
      return;
    }
    AgentRunEntity entity = agentRunMapper.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
        .eq(AgentRunEntity::getRunId, run.runId())
        .last("LIMIT 1"));
    if (entity == null || isTerminal(entity.getStatus())) {
      return;
    }
    entity.setStatus((status == null ? AgentRunStatus.COMPLETED : status).name());
    entity.setLatencyMs(Math.max(0L, latencyMs));
    entity.setDegradedReason(truncate(degradedReason));
    entity.setOutputSummary(truncate(outputSummary));
    entity.setCompletedAt(LocalDateTime.now());
    // Guard the transition in SQL as well as in memory.  Two workers may
    // finish the same run concurrently; only the first RUNNING -> terminal
    // update is allowed to win, so a terminal status is never overwritten.
    agentRunMapper.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
        .eq(AgentRunEntity::getRunId, run.runId())
        .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
        .set(AgentRunEntity::getStatus, entity.getStatus())
        .set(AgentRunEntity::getLatencyMs, entity.getLatencyMs())
        .set(AgentRunEntity::getDegradedReason, entity.getDegradedReason())
        .set(AgentRunEntity::getOutputSummary, entity.getOutputSummary())
        .set(AgentRunEntity::getCompletedAt, entity.getCompletedAt()));
  }

  private boolean isTerminal(String status) {
    if (status == null) {
      return false;
    }
    try {
      return AgentRunStatus.valueOf(status).terminal();
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public void finishQuietly(AgentRunHandle run, AgentRunStatus status, long latencyMs,
                            String degradedReason, String outputSummary) {
    try {
      finish(run, status, latencyMs, degradedReason, outputSummary);
    } catch (Exception e) {
      log.warn("AgentRun 结束失败: runId={}, reason={}", run == null ? null : run.runId(),
          e.getMessage());
    }
  }

  private AgentRunHandle toHandle(AgentRunEntity entity) {
    return new AgentRunHandle(entity.getRunId(), entity.getTraceId(), entity.getCommandId(),
        entity.getSessionId(), entity.getUserId(), entity.getOperation(), entity.getRootSpanId(),
        entity.getCreatedAt());
  }

  public List<AgentRunStepEntity> listBySession(String sessionId, Long userId) {
    return listBySessionKeys(userId, sessionId == null ? List.of() : List.of(sessionId));
  }

  public List<AgentRunStepEntity> listBySessionKeys(Long userId, List<String> sessionIds) {
    List<String> keys = sessionIds == null ? List.of() : sessionIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
    if (userId == null || keys.isEmpty()) {
      return List.of();
    }
    return agentRunStepMapper.selectList(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getUserId, userId)
        .in(AgentRunStepEntity::getSessionId, keys)
        .orderByAsc(AgentRunStepEntity::getId));
  }

  public List<AgentRunStepEntity> listByUser(Long userId) {
    if (userId == null) {
      return List.of();
    }
    return agentRunStepMapper.selectList(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getUserId, userId)
        .orderByAsc(AgentRunStepEntity::getId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteBySession(String sessionId) {
    agentRunStepMapper.delete(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getSessionId, sessionId));
    if (agentRunMapper != null) {
      agentRunMapper.delete(Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getSessionId, sessionId));
    }
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
  }
}
