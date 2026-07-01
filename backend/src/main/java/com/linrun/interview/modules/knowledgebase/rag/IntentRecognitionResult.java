package com.linrun.interview.modules.knowledgebase.rag;

/**
 * 意图识别结果（面试领域多意图 + 实体抽取版）。
 */
public record IntentRecognitionResult(
    boolean related,
    String reason,
    String intent,
    Entities entities
) {

  public IntentRecognitionResult(boolean related, String reason) {
    this(related, reason, related ? InterviewIntent.TECH_KB.name() : InterviewIntent.OFF_TOPIC.name(), null);
  }

  public InterviewIntent resolvedIntent() {
    return related ? InterviewIntent.from(intent) : InterviewIntent.OFF_TOPIC;
  }

  public record Entities(
      String skill,
      Long resumeId,
      Long sessionId,
      String company
  ) {
  }
}
