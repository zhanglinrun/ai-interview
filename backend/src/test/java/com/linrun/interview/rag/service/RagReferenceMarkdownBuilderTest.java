package com.linrun.interview.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 引用来源文本清洗")
class RagReferenceMarkdownBuilderTest {

  @Test
  @DisplayName("去掉图片和链接，避免引用区露出 localhost URL")
  void stripsImagesAndLinksFromSnippet() {
    String raw = """
        ## Redis 为什么快
        ![](http://localhost:29000/ai-interview/converted/49/49/images/4fef6aa4.png)
        因为基于内存，[文档](http://example.com/redis)  elaborates.
        """;

    assertThat(RagReferenceMarkdownBuilder.sanitizeSnippet(raw, 220))
        .doesNotContain("http://")
        .doesNotContain("![]")
        .contains("Redis 为什么快")
        .contains("因为基于内存");
  }

  @Test
  @DisplayName("超长片段按上限截断")
  void truncatesLongSnippet() {
    String snippet = RagReferenceMarkdownBuilder.sanitizeSnippet("甲".repeat(30), 8);

    assertThat(snippet).isEqualTo("甲".repeat(8) + "...");
  }
}
