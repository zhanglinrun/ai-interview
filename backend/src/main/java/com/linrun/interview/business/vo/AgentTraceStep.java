package com.linrun.interview.business.vo;

/**
 * Agent 编排决策轨迹的单个步骤（持久化到 agent_steps，供前端回放）。
 *
 * @param step        步骤序号（从 1 开始）
 * @param role        产生该步骤的角色：planner / interviewer / critic / evaluator / orchestrator
 * @param action      动作名（工具名、plan、ask、critique、finish 等）
 * @param actionInput 动作输入（工具参数 / prompt 摘要）
 * @param observation 动作产出（工具返回 / 题目 / 审核结论）
 */
public record AgentTraceStep(
    int step,
    String role,
    String action,
    String actionInput,
    String observation
) {

  public static final String ROLE_PLANNER = "planner";
  public static final String ROLE_INTERVIEWER = "interviewer";
  public static final String ROLE_CRITIC = "critic";
  public static final String ROLE_EVALUATOR = "evaluator";
  public static final String ROLE_ORCHESTRATOR = "orchestrator";
}
