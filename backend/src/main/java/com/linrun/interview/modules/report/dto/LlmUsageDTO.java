package com.linrun.interview.modules.report.dto;

import com.linrun.interview.modules.report.model.LlmUsageStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LlmUsageDTO(
    String usageId,
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
    BigDecimal estimatedCost,
    String currency,
    int retryCount,
    String degradedReason,
    LocalDateTime createdAt
) {
}
