package com.linrun.interview.rag.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.model.QueryRequest;
import com.linrun.interview.rag.model.QueryResponse;
import com.linrun.interview.rag.model.RagDatasetResult;
import com.linrun.interview.rag.model.RagQaExportRequest;
import com.linrun.interview.rag.model.RagQaExportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线 Q&A Dataset 生成（对齐业界实践 DatasetController）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDatasetService {

  private final KnowledgeBaseQueryService queryService;

  public RagDatasetResult generate(List<Long> knowledgeBaseIds, String question) {
    UserContext.requireUserId();
    long start = System.nanoTime();
    QueryResponse response = queryService.queryKnowledgeBase(
        new QueryRequest(knowledgeBaseIds, question));
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    log.info("[RagDatasetService] 生成 dataset: kbIds={}, latencyMs={}", knowledgeBaseIds, latencyMs);
    return new RagDatasetResult(
        question,
        response.answer(),
        response.sources() != null ? response.sources() : List.of(),
        null,
        null,
        latencyMs);
  }

  /**
   * 批量导出 RAGAS 评测样本：每题走完整 RAG 生成，并返回同一次检索得到的 contexts。
   *
   * <p>answer 与 contexts 由 {@code queryForEvaluation} 的同一次 augment 产生，返回完整 chunk
   * 文本而非截断 snippet，避免 RAGAS 评测到「没有真正喂给模型」的第二次检索结果。
   * ground_truth 由调用方（评测集关键点）透传，本方法不臆造参考答案。
   */
  public RagQaExportResponse exportQa(List<Long> knowledgeBaseIds,
                                      List<RagQaExportRequest.Item> items) {
    UserContext.requireUserId();
    List<RagQaExportResponse.Sample> samples = new ArrayList<>();
    for (RagQaExportRequest.Item item : items) {
      String question = item.question();
      if (question == null || question.isBlank()) {
        continue;
      }
      long start = System.nanoTime();
      KnowledgeBaseQueryService.EvaluationQueryResult execution =
          queryService.queryForEvaluation(knowledgeBaseIds, question);
      long latencyMs = execution.latencyMs() > 0
          ? execution.latencyMs()
          : (System.nanoTime() - start) / 1_000_000;
      List<String> contexts = execution.contexts().stream()
          .map(segment -> segment == null ? null : segment.text())
          .filter(t -> t != null && !t.isBlank())
          .toList();
      samples.add(new RagQaExportResponse.Sample(
          item.id(), item.source(), item.difficulty(), question, execution.answer(), contexts,
          item.referenceAnswer(), item.groundTruth(), latencyMs, execution.noEvidence(),
          execution.routeSource(), execution.routeIntent(), execution.routeConfidence(),
          execution.routeReasoning()));
    }
    log.info("[RagDatasetService] export-qa 完成: kbIds={}, 题量={}", knowledgeBaseIds, samples.size());
    return new RagQaExportResponse(samples.size(), samples);
  }
}
