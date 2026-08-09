package com.linrun.interview.rag.constant;

/**
 * 面试领域 RAG 意图（对齐业界实践 多意图路由，按面试场景裁剪）。
 */
public enum InterviewIntent {

  TECH_KB("rag/knowledgebase-query-system.txt"),
  CODE_REVIEW("rag/intent/code-review.txt"),
  RESUME_STATS("rag/intent/resume-stats.txt"),
  INTERVIEW_PREP("rag/intent/interview-prep.txt"),
  SCHEDULE("rag/intent/schedule.txt"),
  CAREER("rag/intent/career.txt"),
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
