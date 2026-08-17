package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StructuredAwareReranker")
class StructuredAwareRerankerTest {

  @Test
  @DisplayName("拆分结构化与非结构化内容")
  void partitionStructuredAndUnstructured() {
    Content structured = Content.from(TextSegment.from("sql-result",
        Metadata.from(Map.of(MetadataKeyConstant.SKIP_RERANK, "true"))));
    Content unstructured = Content.from(TextSegment.from("doc-chunk"));

    StructuredAwareReranker.Partition partition =
        StructuredAwareReranker.partition(List.of(unstructured, structured));

    assertThat(partition.structured()).extracting(c -> c.textSegment().text())
        .containsExactly("sql-result");
    assertThat(partition.unstructured()).extracting(c -> c.textSegment().text())
        .containsExactly("doc-chunk");
  }

  @Test
  @DisplayName("合并时结构化结果排在 rerank 结果之前")
  void mergeStructuredFirst() {
    Content structured = RagContentUtil.markAsSkipRerank(
        Content.from(TextSegment.from("sql-result")));
    Content reranked = Content.from(TextSegment.from("doc-chunk"));

    StructuredAwareReranker.Partition partition =
        StructuredAwareReranker.partition(List.of(structured, reranked));
    List<Content> merged = StructuredAwareReranker.merge(partition, List.of(reranked));

    assertThat(merged).extracting(c -> c.textSegment().text())
        .containsExactly("sql-result", "doc-chunk");
  }
}
