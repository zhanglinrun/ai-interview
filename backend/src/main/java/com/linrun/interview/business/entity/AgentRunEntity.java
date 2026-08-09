package com.linrun.interview.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 一次 Agent 编排运行的摘要；步骤单独存放在 agent_steps，便于按运行回放。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_runs")
public class AgentRunEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private String runId;
  private String traceId;
  private String commandId;
  private String operation;
  private String rootSpanId;
  private Long userId;
  private String sessionId;
  private Integer questionIndex;
  private String status;
  private String inputSummary;
  private String outputSummary;
  private Long latencyMs;
  private String degradedReason;
  private LocalDateTime createdAt;
  private LocalDateTime completedAt;
}
