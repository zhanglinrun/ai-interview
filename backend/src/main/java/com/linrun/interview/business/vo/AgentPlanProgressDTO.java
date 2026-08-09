package com.linrun.interview.business.vo;

import com.linrun.interview.business.vo.InterviewPlan;

/**
 * Multi-Agent 面试大纲与进度（前端侧栏进度条用）。
 *
 * @param agentMode     是否为 Multi-Agent 编排会话（false 时 plan 通常为 null）
 * @param currentIndex  当前题目索引（0 基）
 * @param plannedTotal  计划总题数
 * @param plan          Planner 产出的大纲（topics/难度曲线/简历&JD 关注点）；旧批量会话为 null
 */
public record AgentPlanProgressDTO(
    boolean agentMode,
    int currentIndex,
    int plannedTotal,
    InterviewPlan plan
) {}

