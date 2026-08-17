package com.linrun.interview.rag.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.SKIP_RERANK;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HybridContentAggregator")
class HybridContentAggregatorTest {

  @Test
  @DisplayName("结构化结果透传且排在非结构化 rerank 结果之前")
  void structuredContentsBypassRerank() {
    Content structured = Content.from(TextSegment.from("sql row",
        Metadata.from(Map.of(SKIP_RERANK, "true"))));
    Content unstructured = Content.from(TextSegment.from("doc chunk"));

    ContentAggregator delegate = queryToContents -> List.of(unstructured);
    HybridContentAggregator aggregator = new HybridContentAggregator(delegate);

    Map<Query, Collection<List<Content>>> input = Map.of(
        Query.from("question"),
        List.of(List.of(structured, unstructured)));

    List<Content> result = aggregator.aggregate(input);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).textSegment().text()).isEqualTo("sql row");
    assertThat(result.get(1).textSegment().text()).isEqualTo("doc chunk");
  }

  @Test
  @DisplayName("仅结构化结果时不调用底层聚合器")
  void structuredOnlySkipsDelegate() {
    Content structured = Content.from(TextSegment.from("cypher row",
        Metadata.from(Map.of(SKIP_RERANK, "1"))));

    ContentAggregator delegate = queryToContents -> {
      throw new IllegalStateException("should not aggregate unstructured");
    };
    HybridContentAggregator aggregator = new HybridContentAggregator(delegate);

    List<Content> result = aggregator.aggregate(Map.of(
        Query.from("question"),
        List.of(List.of(structured))));

    assertThat(result).extracting(content -> content.textSegment().text())
        .containsExactly("cypher row");
  }
}
