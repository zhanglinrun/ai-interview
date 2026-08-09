package com.linrun.interview.business.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Interviewer Agent 单次运行的 LLM 结构化输出（仅出题部分）。
 *
 * <p>作为 {@code InterviewerAiService} 的方法返回值，由 LangChain4j AiServices
 * 自动把 LLM 输出的 JSON 反序列化为此 record。轨迹（{@link AgentTraceStep}）
 * 由工具执行监听器与编排器在运行时收集，不由此对象承载——LLM 只负责出题，
 * 职责清晰。
 *
 * <p>JSON key 用 snake_case 对齐 prompt 要求（{@code is_follow_up}），
 * 通过 {@link JsonProperty} 映射到 camelCase 字段。
 *
 * @param question   生成的下一道面试题
 * @param rationale  出题理由（面向面试官视角的解释，可展示给用户）
 * @param isFollowUp 是否为基于上一轮回答的追问
 * @param evidenceIds 模型实际用于出题的 evidence ID；只能来自本轮候选证据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentQuestionOutput(
    String question,
    String rationale,
    @JsonAlias({"isFollowUp", "followUp"})
    @JsonProperty("is_follow_up") boolean isFollowUp,
    @JsonAlias({"evidenceIds"})
    @JsonProperty("evidence_ids") List<String> evidenceIds
) {

  public AgentQuestionOutput {
    evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
  }

  /** 兼容无证据上下文的内部调用与旧测试。 */
  public AgentQuestionOutput(String question, String rationale, boolean isFollowUp) {
    this(question, rationale, isFollowUp, List.of());
  }
}

