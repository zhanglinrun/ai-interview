package com.linrun.interview.rag.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * RAGAS 生成质量评测导出请求（P4.3）。
 *
 * <p>批量把评测集问题走完整 RAG 生成，导出 {@code {id, source, difficulty, question,
 * answer, contexts, reference_answer, ground_truth}}，
 * 供 {@code eval/ragas/run_ragas.py} 计算 faithfulness / answer_relevancy /
 * context_precision / context_recall。groundTruth/referenceAnswer 均由评测集调用方透传，后端不臆造标准答案。
 */
public record RagQaExportRequest(
    @NotEmpty(message = "至少选择一个知识库") List<Long> knowledgeBaseIds,
    @NotEmpty(message = "至少一条评测问题") List<Item> items
) {

  public record Item(
      String id,
      String source,
      String difficulty,
      String question,
      String groundTruth,
      String referenceAnswer
  ) {
    /** 兼容旧客户端只提交 question + groundTruth 的请求。 */
    public Item(String question, String groundTruth) {
      this(null, null, null, question, groundTruth, null);
    }
  }
}
