package com.linrun.interview.business.vo;

import java.util.List;

/**
 * 面试出题使用的结构化 RAG 证据。
 *
 * @param id              优先使用 chunkId 的稳定证据 ID
 * @param knowledgeBaseId 知识库（文档）ID
 * @param chunkId         原始切块 ID
 * @param embeddingId     向量存储 ID
 * @param source          文件名或知识库来源
 * @param category        文档分类
 * @param score           rerank 分或检索分；不可用时为空
 * @param snippet         注入出题上下文的受限长度片段
 */
public record InterviewEvidence(
    String id,
    Long knowledgeBaseId,
    String chunkId,
    String embeddingId,
    String source,
    String category,
    Double score,
    String snippet
) {

  /** 检索候选与实际送入 Interviewer 的证据集合。 */
  public record Bundle(
      String query,
      List<InterviewEvidence> candidates,
      List<InterviewEvidence> promptEvidence
  ) {
    public Bundle {
      query = query == null ? "" : query;
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      promptEvidence = promptEvidence == null ? List.of() : List.copyOf(promptEvidence);
    }

    public static Bundle empty(String query) {
      return new Bundle(query, List.of(), List.of());
    }

    public List<String> candidateIds() {
      return candidates.stream().map(InterviewEvidence::id).toList();
    }

    public List<String> promptEvidenceIds() {
      return promptEvidence.stream().map(InterviewEvidence::id).toList();
    }
  }
}

