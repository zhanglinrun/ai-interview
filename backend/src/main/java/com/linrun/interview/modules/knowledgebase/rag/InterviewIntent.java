package com.linrun.interview.modules.knowledgebase.rag;

/**
 * 面试领域 RAG 意图（对齐 know-engine 多意图路由，按面试场景裁剪）。
 */
public enum InterviewIntent {

  TECH_KB("knowledgebase-query-system.st"),
  CODE_REVIEW("rag-intent-code-review.st"),
  DATA_QUERY("rag-intent-data-query.st"),
  RESUME_STATS("rag-intent-resume-stats.st"),
  INTERVIEW_PREP("rag-intent-interview-prep.st"),
  SCHEDULE("rag-intent-schedule.st"),
  CAREER("rag-intent-career.st"),
  OFF_TOPIC(null);

  private final String promptFile;

  InterviewIntent(String promptFile) {
    this.promptFile = promptFile;
  }

  public String getPromptFile() {
    return promptFile;
  }

  public static InterviewIntent from(String raw) {
    if (raw == null || raw.isBlank()) {
      return TECH_KB;
    }
    try {
      return InterviewIntent.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return TECH_KB;
    }
  }
}
