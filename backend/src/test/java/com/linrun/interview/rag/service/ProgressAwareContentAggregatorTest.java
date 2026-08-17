package com.linrun.interview.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProgressAwareContentAggregator")
class ProgressAwareContentAggregatorTest {

  @Test
  @DisplayName("聚合前后推送进度文案")
  void emitsAggregateAndGenerateProgress() {
    List<String> progress = new ArrayList<>();
    ContentAggregator delegate = queryToContents ->
        List.of(Content.from(TextSegment.from("chunk")));
    ProgressAwareContentAggregator aggregator =
        new ProgressAwareContentAggregator(delegate, progress::add);

    aggregator.aggregate(Map.of(Query.from("q"), List.<List<Content>>of()));

    assertThat(progress).containsExactly(
        "正在排序筛选结果...",
        "正在生成回答...");
  }
}
