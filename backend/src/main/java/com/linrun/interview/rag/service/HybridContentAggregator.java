package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合内容聚合器。
 *
 * <p>结构化结果（SQL/Cypher，带 {@code skipRerank}）直接透传；向量/全文结果委托底层
 * {@link InterviewReRankingContentAggregator} 做 RRF 融合与 BGE rerank。输出顺序：结构化在前。</p>
 */
public class HybridContentAggregator implements ContentAggregator {

  private final ContentAggregator unstructuredAggregator;

  public HybridContentAggregator(ContentAggregator unstructuredAggregator) {
    this.unstructuredAggregator = unstructuredAggregator;
  }

  @Override
  public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
    if (queryToContents == null || queryToContents.isEmpty()) {
      return new ArrayList<>();
    }

    List<Content> structuredContents = new ArrayList<>();
    Map<Query, Collection<List<Content>>> unstructuredQueryToContents = new LinkedHashMap<>();

    for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
      Query query = entry.getKey();
      Collection<List<Content>> contentLists = entry.getValue();

      List<List<Content>> unstructuredLists = new ArrayList<>();
      for (List<Content> contents : contentLists) {
        List<Content> unstructured = new ArrayList<>();
        for (Content content : contents) {
          if (RagContentUtil.isSkipRerank(content)) {
            structuredContents.add(content);
          } else {
            unstructured.add(content);
          }
        }
        if (!unstructured.isEmpty()) {
          unstructuredLists.add(unstructured);
        }
      }

      if (!unstructuredLists.isEmpty()) {
        unstructuredQueryToContents.put(query, unstructuredLists);
      }
    }

    List<Content> unstructuredResults = unstructuredQueryToContents.isEmpty()
        ? List.of()
        : unstructuredAggregator.aggregate(unstructuredQueryToContents);

    List<Content> combined = new ArrayList<>(structuredContents.size() + unstructuredResults.size());
    combined.addAll(structuredContents);
    combined.addAll(unstructuredResults);
    return combined;
  }
}
