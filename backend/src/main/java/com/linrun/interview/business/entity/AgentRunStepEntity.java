package com.linrun.interview.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Multi-Agent 编排轨迹步骤实体（agent_steps 表）。
 * 记录 Planner/Interviewer/Critic/Evaluator 的每一步决策，按会话回放。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_steps")
public class AgentRunStepEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 归属的 Agent 运行 ID；离线适配器允许为空。 */
  private String runId;

  private String traceId;

  private String spanId;

  private String parentSpanId;

  private Long userId;

  /** 面试会话业务 ID（interview_sessions.session_id） */
  private String sessionId;

  /** 该步骤归属的题号（planner 阶段为 null） */
  private Integer questionIndex;

  /** planner / interviewer / critic / evaluator / orchestrator */
  private String role;

  /** 同一次编排内的步骤序号 */
  private Integer stepOrder;

  /** 动作名：plan / ask / critique / 工具名 / finish 等 */
  private String action;

  private String actionInput;

  private String observation;

  private String status;

  private Long latencyMs;

  private String metadataJson;

  private LocalDateTime createdAt;
}
