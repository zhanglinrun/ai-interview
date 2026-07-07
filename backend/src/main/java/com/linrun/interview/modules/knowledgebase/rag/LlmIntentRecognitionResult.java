package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * LLM 单路语义意图识别结果。
 *
 * <p>该类型只承载模型直接判断，不包含三路融合后的证据，避免 LLM 被要求生成本地规则/样例
 * 相似度等它无法真实知道的字段。
 */
public record LlmIntentRecognitionResult(
    @JsonPropertyDescription("意图识别的推断理由")
    String reason,
    @JsonPropertyDescription("用户问题是否与 AI 面试/技术/简历/求职场景相关")
    boolean related,
    @JsonPropertyDescription("意图：TECH_KB/CODE_REVIEW/DATA_QUERY/RESUME_STATS/INTERVIEW_PREP/SCHEDULE/CAREER/OFF_TOPIC")
    String intent,
    @JsonPropertyDescription("LLM 对该判断的置信度，范围 0.0~1.0")
    Double confidence,
    @JsonPropertyDescription("从用户输入中提取的关键实体")
    IntentRecognitionResult.Entities entities
) {

  public InterviewIntent resolvedIntent() {
    return related ? InterviewIntent.from(intent) : InterviewIntent.OFF_TOPIC;
  }
}
