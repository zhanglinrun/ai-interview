package com.linrun.interview.rag.model;

import com.linrun.interview.rag.constant.InterviewIntent;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 意图识别结果（面试领域多意图 + 实体抽取，LangChain4j Structured Output）。
 */
public record IntentRecognitionResult(
    @JsonPropertyDescription("意图识别的推断理由")
    String reason,
    @JsonPropertyDescription("用户问题是否与 AI 面试/技术/简历/求职场景相关")
    boolean related,
    @JsonPropertyDescription("意图：TECH_KB/CODE_REVIEW/RESUME_STATS/INTERVIEW_PREP/SCHEDULE/CAREER/OFF_TOPIC")
    String intent,
    @JsonPropertyDescription("从用户输入中提取的关键实体")
    Entities entities,
    @JsonPropertyDescription("三路融合后的综合置信度，范围 0.0~1.0")
    Double confidence,
    @JsonPropertyDescription("LLM/相似度/规则三路识别证据")
    List<StrategyScore> strategies,
    @JsonPropertyDescription("是否命中本地意图识别缓存")
    Boolean cached
) {

  public IntentRecognitionResult(String reason, boolean related, String intent, Entities entities) {
    this(reason, related, intent, entities, null, List.of(), false);
  }

  public IntentRecognitionResult(boolean related, String reason) {
    this(reason, related, related ? InterviewIntent.TECH_KB.name() : InterviewIntent.OFF_TOPIC.name(), null);
  }

  public InterviewIntent resolvedIntent() {
    return related ? InterviewIntent.from(intent) : InterviewIntent.OFF_TOPIC;
  }

  public record Entities(
      @JsonPropertyDescription("技能/方向，如 Java、前端")
      String skill,
      @JsonPropertyDescription("简历 ID（数字）")
      Long resumeId,
      @JsonPropertyDescription("面试会话 ID（数字）")
      Long sessionId,
      @JsonPropertyDescription("公司名")
      String company
  ) {
  }

  public record StrategyScore(
      @JsonPropertyDescription("识别通道：llm/vector/rule")
      String strategy,
      @JsonPropertyDescription("该通道给出的意图")
      String intent,
      @JsonPropertyDescription("该通道原始置信度，范围 0.0~1.0")
      double confidence,
      @JsonPropertyDescription("该通道融合权重")
      double weight,
      @JsonPropertyDescription("该通道加权得分")
      double weightedScore,
      @JsonPropertyDescription("该通道命中理由或降级说明")
      String reason
  ) {
  }
}
