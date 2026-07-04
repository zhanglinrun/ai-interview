package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

/**
 * RAGAS 生成质量评测导出结果（P4.3）。
 *
 * <p>{@link Sample#contexts()} 为真实召回的完整 chunk 文本（走 {@code retrieveForEvaluation} 同一增强链路），
 * {@link Sample#answer()} 为完整 RAG 生成回答。导出为 JSONL 后交给 RAGAS 评测。
 */
public record RagQaExportResponse(
    int total,
    List<Sample> records
) {

  public record Sample(
      String question,
      String answer,
      List<String> contexts,
      String groundTruth,
      long latencyMs
  ) {}
}
