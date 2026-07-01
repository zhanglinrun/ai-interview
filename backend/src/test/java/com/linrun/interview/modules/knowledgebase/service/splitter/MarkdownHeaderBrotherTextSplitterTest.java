package com.linrun.interview.modules.knowledgebase.service.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_ID;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_INDEX;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_TOTAL;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.CHUNK_ID;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.HEADER_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownHeaderBrotherTextSplitter 测试")
class MarkdownHeaderBrotherTextSplitterTest {

  @Test
  @DisplayName("超长章节应切成兄弟块并写入 brother 元数据")
  void longSectionCreatesBrotherChunks() {
    String body = "段落内容".repeat(120);
    String markdown = """
        # 根标题
        ## 长章节
        %s
        """.formatted(body);

    MarkdownHeaderBrotherTextSplitter splitter = new MarkdownHeaderBrotherTextSplitter(200, 20);
    List<TextSegment> segments = splitter.split(Document.from(markdown));

    assertThat(segments).isNotEmpty();
    assertThat(segments).allMatch(s -> s.metadata().getString(CHUNK_ID) != null);

    Set<String> brotherGroups = segments.stream()
        .map(s -> s.metadata().getString(BROTHER_CHUNK_ID))
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());
    assertThat(brotherGroups).isNotEmpty();

    long withBrotherIndex = segments.stream()
        .filter(s -> s.metadata().containsKey(BROTHER_CHUNK_INDEX))
        .count();
    assertThat(withBrotherIndex).isGreaterThan(1);

    segments.stream()
        .filter(s -> s.metadata().containsKey(BROTHER_CHUNK_TOTAL))
        .forEach(s -> assertThat(s.metadata().getInteger(BROTHER_CHUNK_TOTAL)).isGreaterThan(1));
  }

  @Test
  @DisplayName("多级标题应写入 headerLevel 元数据")
  void multiLevelHeadersWriteHeaderLevel() {
    String markdown = """
        # 一级
        根内容
        ## 二级
        子内容
        """;

    MarkdownHeaderBrotherTextSplitter splitter = new MarkdownHeaderBrotherTextSplitter(500, 0);
    List<TextSegment> segments = splitter.split(Document.from(markdown));

    assertThat(segments).isNotEmpty();
    assertThat(segments.stream().anyMatch(s -> s.metadata().containsKey(HEADER_LEVEL))).isTrue();
  }
}
