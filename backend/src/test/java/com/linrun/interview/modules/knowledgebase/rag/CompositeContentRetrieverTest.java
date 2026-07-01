package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("组合检索器测试")
class CompositeContentRetrieverTest {

  @Test
  @DisplayName("应合并多路检索结果")
  void mergesDelegates() {
    ContentRetriever first = query -> List.of(Content.from("a"));
    ContentRetriever second = query -> List.of(Content.from("b"));
    CompositeContentRetriever composite = new CompositeContentRetriever(List.of(first, second));

    List<Content> results = composite.retrieve(new Query("test"));

    assertThat(results).hasSize(2);
    assertThat(results.get(0).textSegment().text()).isEqualTo("a");
    assertThat(results.get(1).textSegment().text()).isEqualTo("b");
  }
}
