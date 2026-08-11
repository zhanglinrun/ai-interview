package com.linrun.interview.rag.model;

import java.util.List;

/**
 * RAGAS 生成质量评测导出结果（P4.3）。
 *
 * <p>{@link Sample#contexts()} 为与 {@link Sample#answer()} 同一次 augment 产生的真实召回完整
 * chunk 文本，{@link Sample#answer()} 为完整 RAG 生成回答。导出为 JSONL 后交给 RAGAS 评测。
 */
public record RagQaExportResponse(
    int total,
    List<Sample> records
) {

  public record Sample(
      String id,
      String source,
      String difficulty,
      String question,
      String answer,
      List<String> contexts,
      String referenceAnswer,
      String groundTruth,
      long latencyMs,
      boolean noEvidence,
      String routeSource,
      String routeIntent,
      Double routeConfidence,
      String routeReasoning
  ) {}
}
