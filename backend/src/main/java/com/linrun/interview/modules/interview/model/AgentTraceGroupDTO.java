package com.linrun.interview.modules.interview.model;

import java.util.List;

/**
 * Multi-Agent 编排决策轨迹（按题号分组，供前端回放）。
 *
 * @param questionIndex 归属题号；{@code null} 表示 PLANNING 阶段（Planner 出大纲）
 * @param steps         该阶段按顺序的决策步骤
 */
public record AgentTraceGroupDTO(
    Integer questionIndex,
    List<AgentTraceStepDTO> steps
) {

  /**
   * 单个决策步骤（脱敏自 agent_run_steps，不直接暴露实体）。
   *
   * @param role        planner / interviewer / critic / evaluator / orchestrator
   * @param action      动作名（plan / ask / critique / 工具名 / finish 等）
   * @param actionInput 动作输入摘要
   * @param observation 动作产出（题目 / 审核结论 / 工具返回）
   */
  public record AgentTraceStepDTO(
      int step,
      String role,
      String action,
      String actionInput,
      String observation
  ) {}
}
