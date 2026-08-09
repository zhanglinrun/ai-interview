package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.LlmUsageStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_usage_records")
public class LlmUsageRecordEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String usageId;
  private Long userId;
  private String sessionId;
  private String reportId;
  private String operation;
  private String provider;
  private String model;
  private LlmUsageStatus status;
  private Long latencyMs;
  private Integer inputTokens;
  private Integer outputTokens;
  private Integer totalTokens;
  private BigDecimal estimatedCost;
  private String currency;
  private Integer retryCount;
  private String degradedReason;
  private String traceId;
  private String agentRunId;
  private String ragRunId;
  private String spanId;
  private LocalDateTime createdAt;
}
