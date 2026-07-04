package com.linrun.interview.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * RAGAS 生成质量评测导出请求（P4.3）。
 *
 * <p>批量把评测集问题走完整 RAG 生成，导出 {@code {question, answer, contexts, ground_truth}}，
 * 供 {@code eval/ragas/run_ragas.py} 计算 faithfulness / answer_relevancy /
 * context_precision / context_recall。ground_truth 由评测集关键点组装后经此接口透传回导出结果。
 */
public record RagQaExportRequest(
    @NotEmpty(message = "至少选择一个知识库") List<Long> knowledgeBaseIds,
    @NotEmpty(message = "至少一条评测问题") List<Item> items
) {

  public record Item(
      String question,
      String groundTruth
  ) {}
}
