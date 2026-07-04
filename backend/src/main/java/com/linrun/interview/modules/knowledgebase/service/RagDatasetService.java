package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.model.QueryRequest;
import com.linrun.interview.modules.knowledgebase.model.QueryResponse;
import com.linrun.interview.modules.knowledgebase.model.RagDatasetResult;
import com.linrun.interview.modules.knowledgebase.model.RagQaExportRequest;
import com.linrun.interview.modules.knowledgebase.model.RagQaExportResponse;
import dev.langchain4j.data.segment.TextSegment;
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
   * 批量导出 RAGAS 评测样本：每题走完整 RAG 生成得到 answer，并单独取召回 chunk 作为 contexts。
   *
   * <p>contexts 走 {@code retrieveForEvaluation}（与生成同一增强链路，返回完整 chunk 文本，
   * 非截断 snippet），保证 RAGAS 的 context_precision / context_recall 基于真实召回上下文。
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
      List<TextSegment> retrieved = queryService.retrieveForEvaluation(knowledgeBaseIds, question);
      QueryResponse response = queryService.queryKnowledgeBase(
          new QueryRequest(knowledgeBaseIds, question));
      long latencyMs = (System.nanoTime() - start) / 1_000_000;
      List<String> contexts = retrieved.stream()
          .map(TextSegment::text)
          .filter(t -> t != null && !t.isBlank())
          .toList();
      samples.add(new RagQaExportResponse.Sample(
          question, response.answer(), contexts, item.groundTruth(), latencyMs));
    }
    log.info("[RagDatasetService] export-qa 完成: kbIds={}, 题量={}", knowledgeBaseIds, samples.size());
    return new RagQaExportResponse(samples.size(), samples);
  }
}
