package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.model.QueryRequest;
import com.linrun.interview.modules.knowledgebase.model.QueryResponse;
import com.linrun.interview.modules.knowledgebase.model.RagDatasetResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 离线 Q&A Dataset 生成（对齐 know-engine DatasetController）。
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
}
