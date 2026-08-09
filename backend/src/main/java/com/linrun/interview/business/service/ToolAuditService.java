package com.linrun.interview.business.service;

import com.linrun.interview.business.entity.AgentToolRunEntity;
import com.linrun.interview.business.mapper.AgentToolRunMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAuditService {
  private static final int SUMMARY_LIMIT = 1_000;

  private final AgentToolRunMapper mapper;

  public void recordQuietly(ToolExecutionContext context, String toolName, ToolResult<?> result,
                            String inputSummary, long latencyMs) {
    try {
      mapper.insert(AgentToolRunEntity.builder()
          .toolRunId("tool-" + UUID.randomUUID())
        .agentRunId(context == null ? null : context.agentRunId())
        .ragRunId(result == null ? null : result.ragRunId())
          .traceId(context == null ? null : context.traceId())
          .sessionId(context == null ? null : context.sessionId())
          .userId(context == null ? null : context.userId())
          .spanId(context == null ? null : context.spanId())
          .toolName(toolName)
          .status(result.status().name())
          .cacheHit(result.cacheHit())
          .retryCount(result.retryCount())
          .inputSummary(limit(inputSummary))
          .outputSummary(limit(result.summary()))
          .fallbackReason(limit(result.degradedReason()))
          .errorCode(limit(result.errorCode()))
          .latencyMs(latencyMs)
          .startedAt(LocalDateTime.now().minusNanos(Math.max(0L, latencyMs) * 1_000_000L))
          .completedAt(LocalDateTime.now())
          .build());
    } catch (Exception e) {
      log.warn("工具审计写入失败: tool={}, reason={}", toolName, e.getMessage());
    }
  }

  private String limit(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= SUMMARY_LIMIT ? value : value.substring(0, SUMMARY_LIMIT) + "…";
  }
}
