package com.linrun.interview.modules.interview.agent.model;

/**
 * Critic Agent 对一道候选题目的审核结论。
 *
 * @param approved  是否通过（不通过时触发 Interviewer 携带 retryHint 重生成）
 * @param score     题目质量分 0-100
 * @param feedback  审核意见（说明为什么通过/不通过）
 * @param retryHint 不通过时给 Interviewer 的改进指令（Reflexion 输入）
 */
public record CriticVerdict(
    boolean approved,
    int score,
    String feedback,
    String retryHint
) {}
