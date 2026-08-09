package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 多个 ES scope/channel 检索器的轻量组合器，用作结构化检索失败时的统一回退。 */
public class CompositeContentRetriever implements ContentRetriever {

  private final List<ContentRetriever> delegates;

  public CompositeContentRetriever(Collection<? extends ContentRetriever> delegates) {
    this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
  }

  @Override
  public List<Content> retrieve(Query query) {
    if (delegates.isEmpty()) {
      return List.of();
    }
    List<Content> results = new ArrayList<>();
    for (ContentRetriever delegate : delegates) {
      if (delegate != null) {
        List<Content> current = delegate.retrieve(query);
        if (current != null) {
          results.addAll(current);
        }
      }
    }
    return List.copyOf(results);
  }

  public List<ContentRetriever> delegates() {
    return delegates;
  }
}
