package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合多个 {@link ContentRetriever}，并行语义由 {@code DefaultRetrievalAugmentor} 负责；
 * 本类用于 SQL/Neo4j 降级路径需要同时命中多路 ES 的场景。
 */
public class CompositeContentRetriever implements ContentRetriever {

  private final List<ContentRetriever> delegates;

  public CompositeContentRetriever(List<ContentRetriever> delegates) {
    this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
  }

  @Override
  public List<Content> retrieve(Query query) {
    if (delegates.isEmpty()) {
      return List.of();
    }
    if (delegates.size() == 1) {
      return delegates.getFirst().retrieve(query);
    }
    List<Content> merged = new ArrayList<>();
    for (ContentRetriever delegate : delegates) {
      List<Content> batch = delegate.retrieve(query);
      if (batch != null && !batch.isEmpty()) {
        merged.addAll(batch);
      }
    }
    return merged;
  }
}
