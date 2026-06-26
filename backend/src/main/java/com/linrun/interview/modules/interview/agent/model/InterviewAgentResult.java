package com.linrun.interview.modules.interview.agent.model;

import java.util.List;

/**
 * 面试官 Agent 一次运行的产出。
 *
 * @param question   生成的下一道面试题
 * @param rationale  出题理由（面向面试官视角的解释，可展示给用户）
 * @param isFollowUp 是否为基于上一轮回答的追问
 * @param trace      完整的 ReAct 决策轨迹（think-act-observe 多轮）
 * @param rounds     实际经历的决策轮数
 */
public record InterviewAgentResult(
    String question,
    String rationale,
    boolean isFollowUp,
    List<AgentTraceStep> trace,
    int rounds
) {}
