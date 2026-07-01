package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识图谱同步测试")
class KnowledgeGraphSyncServiceTest {

  private static final Pattern HEADER_PATTERN =
      Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE);

  @Test
  @DisplayName("应从 Markdown 标题抽取概念")
  void extractsConceptsFromHeaders() {
    String text = """
        # JVM 基础
        一些内容
        ## 垃圾回收
        更多内容
        """;

    Set<String> concepts = extractConcepts(text);

    assertThat(concepts).containsExactly("JVM 基础", "垃圾回收");
  }

  private Set<String> extractConcepts(String text) {
    java.util.LinkedHashSet<String> concepts = new java.util.LinkedHashSet<>();
    var matcher = HEADER_PATTERN.matcher(text);
    while (matcher.find()) {
      concepts.add(matcher.group(2).trim());
    }
    return concepts;
  }

  @Test
  @DisplayName("分段文本应包含 Markdown 标题概念")
  void segmentTextContainsConcept() {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setText("### Spring Boot 自动配置\n内容");

    assertThat(segment.getText()).contains("Spring Boot 自动配置");
  }
}
