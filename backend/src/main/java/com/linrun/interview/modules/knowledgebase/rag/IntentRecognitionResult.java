package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 意图识别结果（面试领域多意图 + 实体抽取，LangChain4j Structured Output）。
 */
public record IntentRecognitionResult(
    @JsonPropertyDescription("意图识别的推断理由")
    String reason,
    @JsonPropertyDescription("用户问题是否与 AI 面试/技术/简历/求职场景相关")
    boolean related,
    @JsonPropertyDescription("意图：TECH_KB/CODE_REVIEW/DATA_QUERY/RESUME_STATS/INTERVIEW_PREP/SCHEDULE/CAREER/OFF_TOPIC")
    String intent,
    @JsonPropertyDescription("从用户输入中提取的关键实体")
    Entities entities
) {

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
}
