package com.linrun.interview.modules.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.modules.report.dto.LlmUsageDTO;
import com.linrun.interview.modules.report.mapper.LlmUsageRecordMapper;
import com.linrun.interview.modules.report.model.LlmUsageRecordEntity;
import com.linrun.interview.modules.report.model.LlmUsageStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LlmUsageService {

  private final LlmUsageRecordMapper mapper;

  public void record(Capture capture) {
    if (capture == null || capture.userId() == null || capture.userId() <= 0) {
      return;
    }
    mapper.insert(LlmUsageRecordEntity.builder()
        .usageId(UUID.randomUUID().toString())
        .userId(capture.userId())
        .sessionId(trim(capture.sessionId(), 36))
        .reportId(trim(capture.reportId(), 36))
        .operation(defaultText(capture.operation(), "UNKNOWN", 64))
        .provider(defaultText(capture.provider(), "BYOK", 32))
        .model(trim(capture.model(), 191))
        .status(capture.status() == null ? LlmUsageStatus.FAILED : capture.status())
        .latencyMs(Math.max(0L, capture.latencyMs()))
        .inputTokens(nonNegative(capture.inputTokens()))
        .outputTokens(nonNegative(capture.outputTokens()))
        .totalTokens(nonNegative(capture.totalTokens()))
        .estimatedCost(null)
        .currency(null)
        .retryCount(Math.max(0, capture.retryCount()))
        .degradedReason(trim(capture.degradedReason(), 255))
        .traceId(trim(capture.traceId(), 64))
        .createdAt(LocalDateTime.now())
        .build());
  }

  public List<LlmUsageDTO> list(
      Long userId,
      String sessionId,
      String reportId,
      int requestedLimit
  ) {
    int limit = Math.max(1, Math.min(200, requestedLimit));
    return mapper.selectList(Wrappers.<LlmUsageRecordEntity>lambdaQuery()
            .eq(LlmUsageRecordEntity::getUserId, userId)
            .eq(sessionId != null && !sessionId.isBlank(),
                LlmUsageRecordEntity::getSessionId, sessionId)
            .eq(reportId != null && !reportId.isBlank(),
                LlmUsageRecordEntity::getReportId, reportId)
            .orderByDesc(LlmUsageRecordEntity::getCreatedAt)
            .last("LIMIT " + limit))
        .stream()
        .map(this::toDto)
        .toList();
  }

  private LlmUsageDTO toDto(LlmUsageRecordEntity entity) {
    return new LlmUsageDTO(
        entity.getUsageId(), entity.getSessionId(), entity.getReportId(),
        entity.getOperation(), entity.getProvider(), entity.getModel(), entity.getStatus(),
        entity.getLatencyMs() == null ? 0L : entity.getLatencyMs(),
        entity.getInputTokens(), entity.getOutputTokens(), entity.getTotalTokens(),
        entity.getEstimatedCost(), entity.getCurrency(),
        entity.getRetryCount() == null ? 0 : entity.getRetryCount(),
        entity.getDegradedReason(), entity.getCreatedAt());
  }

  private Integer nonNegative(Integer value) {
    return value == null ? null : Math.max(0, value);
  }

  private String defaultText(String value, String fallback, int maxLength) {
    String resolved = value == null || value.isBlank() ? fallback : value;
    return trim(resolved, maxLength);
  }

  private String trim(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength
        ? normalized : normalized.substring(0, maxLength);
  }

  public record Capture(
      Long userId,
      String sessionId,
      String reportId,
      String operation,
      String provider,
      String model,
      LlmUsageStatus status,
      long latencyMs,
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens,
      int retryCount,
      String degradedReason,
      String traceId
  ) {
  }
}
