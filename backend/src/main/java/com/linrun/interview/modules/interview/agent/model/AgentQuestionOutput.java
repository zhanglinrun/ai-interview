package com.linrun.interview.modules.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 面试官 Agent 单次运行的 LLM 结构化输出（仅出题部分）。
 *
 * <p>作为 {@code InterviewAgentAiService} 的方法返回值，由 LangChain4j AiServices
 * 自动把 LLM 输出的 JSON 反序列化为此 record。轨迹（{@link AgentTraceStep}）与轮数
 * 由 {@code ToolExecutedEventListener} 在运行时收集，不由此对象承载——LLM 只负责出题，
 * 职责清晰。
 *
 * <p>JSON key 用 snake_case 对齐 prompt 要求（{@code is_follow_up}），
 * 通过 {@link JsonProperty} 映射到 camelCase 字段。
 *
 * @param question   生成的下一道面试题
 * @param rationale  出题理由（面向面试官视角的解释，可展示给用户）
 * @param isFollowUp 是否为基于上一轮回答的追问
 */
public record AgentQuestionOutput(
    String question,
    String rationale,
    @JsonProperty("is_follow_up") boolean isFollowUp
) {
}
