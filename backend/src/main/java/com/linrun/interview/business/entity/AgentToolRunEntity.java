package com.linrun.interview.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_tool_runs")
public class AgentToolRunEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String toolRunId;
    private String agentRunId;
    private String ragRunId;
  private String traceId;
  private String sessionId;
  private Long userId;
  private String spanId;
  private String parentSpanId;
  private String toolName;
  private String status;
  private Boolean cacheHit;
  private Integer retryCount;
  private String inputSummary;
  private String outputSummary;
  private String fallbackReason;
  private String errorCode;
  private Long latencyMs;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
}
