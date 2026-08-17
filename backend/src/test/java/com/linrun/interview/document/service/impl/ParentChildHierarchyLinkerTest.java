package com.linrun.interview.document.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.linrun.interview.rag.constant.MetadataKeyConstant.HEADER_LEVEL;
import static com.linrun.interview.rag.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static com.linrun.interview.rag.constant.MetadataKeyConstant.SKIP_EMBEDDING;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("空分类标题只做面包屑")
class ParentChildHierarchyLinkerTest {

  @Test
  @DisplayName("只有标题的分类段应识别为空分类头")
  void headingOnlyIsCategory() {
    assertThat(ParentChildHierarchyLinker.isHeadingOnly("## 基础")).isTrue();
    assertThat(ParentChildHierarchyLinker.isCategoryHeader("## 基础", 2, 2)).isTrue();
    assertThat(ParentChildHierarchyLinker.isHeadingOnly("# RocketMQ\n## 概览")).isTrue();
    assertThat(ParentChildHierarchyLinker.isHeadingOnly("## 1.为什么要用消息队列?\n解耦。")).isFalse();
  }

  @Test
  @DisplayName("空分类头写入后续问题，自己不升格为不入库父块")
  void mergesCategoryAsPrefixWithoutSkipEmbedding() {
    List<ParentChildHierarchyLinker.Section> linked = ParentChildHierarchyLinker.link(List.of(
        section("# RocketMQ\n导语", 1, "root"),
        section("## 基础", 2, "cat"),
        section("## 1.为什么要用消息队列?\n解耦。", 2, "q1"),
        section("## 2.如何保证不丢失?\n确认。", 2, "q2")));

    assertThat(linked).noneMatch(section ->
        Integer.valueOf(1).equals(section.metadata().get(SKIP_EMBEDDING)));
    assertThat(linked).noneMatch(section -> section.metadata().get(PARENT_CHUNK_ID) != null);
    assertThat(linked).noneMatch(section -> "cat".equals(section.metadata().get(CHUNK_ID)));
    assertThat(linked.stream().map(ParentChildHierarchyLinker.Section::content).toList())
        .anyMatch(text -> text.contains("## 基础") && text.contains("为什么要用消息队列"))
        .anyMatch(text -> text.contains("## 基础") && text.contains("如何保证不丢失"));
  }

  @Test
  @DisplayName("文末空标题并入上一节，不单独成检索段")
  void trailingHeadingOnlyJoinsPrevious() {
    List<ParentChildHierarchyLinker.Section> linked = ParentChildHierarchyLinker.link(List.of(
        section("## 1.为什么要用消息队列?\n解耦。", 2, "q1"),
        section("## 小结", 2, "tail")));

    assertThat(linked).hasSize(1);
    assertThat(linked.get(0).content()).contains("为什么要用消息队列", "## 小结");
  }

  private static ParentChildHierarchyLinker.Section section(String content, int level, String chunkId) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(HEADER_LEVEL, level);
    metadata.put(CHUNK_ID, chunkId);
    return new ParentChildHierarchyLinker.Section(content, metadata);
  }
}
