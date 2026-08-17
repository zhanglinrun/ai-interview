package com.linrun.interview.document.service;

import com.linrun.interview.document.constant.SplitType;
import com.linrun.interview.document.service.impl.MarkdownHeaderParentTextSplitter;
import com.linrun.interview.document.vo.DocumentSplitParam;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Markdown 父子切块")
class MarkdownHeaderParentTextSplitterTest {

  @Test
  @DisplayName("titleLevel=2 时超长二级标题章才整章留父块，再按三级标题切子块")
  void parentChildUsesDeeperHeadingsForChildren() {
    String markdown = """
        # 02 · 双指针与滑动窗口

        来源：labuladong

        ## 一、本质理解

        双指针把 O(n^2) 降到 O(n)。

        ## 二、对撞指针（左右指针）

        ### 核心思路
        %s

        ### 适用条件
        %s

        ### 标准模板
        %s
        """.formatted("从两端向中间夹。".repeat(25), "数组有序。".repeat(25), "left 和 right 移动。".repeat(25));

    MarkdownHeaderParentTextSplitter splitter = (MarkdownHeaderParentTextSplitter)
        DocumentSplitterFactory.getInstance(
            new DocumentSplitParam(SplitType.TITLE.name(), 80, 10, 2, null, null));
    List<TextSegment> chunks = splitter.split(Document.from(markdown));

    List<TextSegment> parents = chunks.stream()
        .filter(chunk -> Integer.valueOf(1).equals(chunk.metadata().getInteger(MetadataKeyConstant.SKIP_EMBEDDING)))
        .toList();
    List<TextSegment> children = chunks.stream()
        .filter(chunk -> chunk.metadata().getString(MetadataKeyConstant.PARENT_CHUNK_ID) != null)
        .toList();

    assertThat(parents).isNotEmpty();
    assertThat(parents.stream().map(TextSegment::text).toList())
        .anyMatch(text -> text.contains("### 核心思路") && text.contains("### 适用条件"));
    assertThat(children).hasSizeGreaterThanOrEqualTo(3);
    assertThat(children.stream().map(TextSegment::text).toList())
        .anyMatch(text -> text.contains("核心思路") && !text.contains("### 适用条件"))
        .anyMatch(text -> text.contains("### 适用条件"))
        .anyMatch(text -> text.contains("### 标准模板"));
  }

  @Test
  @DisplayName("默认 titleLevel=3 时三级标题先独立成段，短段不升格父子")
  void defaultTitleLevelSplitsH3AsStandalone() {
    String markdown = """
        # 微服务
        导语。
        ## 1.什么是微服务?
        微服务把单体拆成可独立部署的服务。
        ### 定义
        一组小服务协同工作。
        ### 对比
        和单体架构相比，服务边界更清晰。
        """;

    List<TextSegment> chunks = DocumentSplitterFactory.getInstance(
            new DocumentSplitParam(SplitType.TITLE.name(), 800, 80, null, null, null))
        .split(Document.from(markdown));

    assertThat(chunks.stream().map(TextSegment::text).toList())
        .anyMatch(text -> text.contains("### 定义") && !text.contains("### 对比"))
        .anyMatch(text -> text.contains("### 对比") && !text.contains("### 定义"));
    assertThat(chunks).noneMatch(chunk ->
        Integer.valueOf(1).equals(chunk.metadata().getInteger(MetadataKeyConstant.SKIP_EMBEDDING)));
  }

  @Test
  @DisplayName("短标题段自己入库检索，不因为有上级标题就变成子分段")
  void shortHeadingSectionStaysRetrievable() {
    String markdown = """
        # 标题
        简短导语。
        ## 一、本质理解
        左右指针、快慢指针、滑动窗口。
        """;

    MarkdownHeaderParentTextSplitter splitter = new MarkdownHeaderParentTextSplitter(2, false, false, 800, 80);
    List<TextSegment> chunks = splitter.split(Document.from(markdown));

    assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    assertThat(chunks).noneMatch(chunk ->
        Integer.valueOf(1).equals(chunk.metadata().getInteger(MetadataKeyConstant.SKIP_EMBEDDING)));
    assertThat(chunks).noneMatch(chunk ->
        chunk.metadata().getString(MetadataKeyConstant.PARENT_CHUNK_ID) != null);
  }

  @Test
  @DisplayName("空分类标题并入后续问题，短题保持检索段而不是整章父块")
  void categoryHeadingPrefixesQuestionsWithoutBecomingParent() {
    String markdown = """
        # RocketMQ
        面试题整理。
        ## 基础
        ## 1.为什么要使用消息队列呢?
        解耦：电商下单后通知库存和积分。
        ## 2.如何保证消息不丢失?
        生产者、Broker、消费者都要有确认。
        ## 进阶
        ## 1.如何保证顺序消费?
        单队列加单消费者。
        """;

    List<TextSegment> chunks = DocumentSplitterFactory.getInstance(
            new DocumentSplitParam(SplitType.TITLE.name(), 800, 80, 3, null, null))
        .split(Document.from(markdown));

    assertThat(chunks).noneMatch(chunk ->
        Integer.valueOf(1).equals(chunk.metadata().getInteger(MetadataKeyConstant.SKIP_EMBEDDING)));
    assertThat(chunks).noneMatch(chunk ->
        chunk.metadata().getString(MetadataKeyConstant.PARENT_CHUNK_ID) != null);
    assertThat(chunks.stream().map(TextSegment::text).toList())
        .anyMatch(text -> text.contains("## 基础") && text.contains("为什么要使用消息队列"))
        .anyMatch(text -> text.contains("## 基础") && text.contains("如何保证消息不丢失"))
        .anyMatch(text -> text.contains("## 进阶") && text.contains("顺序消费"))
        .noneMatch(text -> text.trim().equals("## 基础"));
  }

  @Test
  @DisplayName("保留代码缩进和空行，不再预删空行")
  void preservesCodeIndentAndBlankLines() {
    String markdown = """
        # 模板
        ## 示例

        ```java
        public class Demo {
            public int run() {
                return 1;
            }
        }
        ```
        """;

    MarkdownHeaderParentTextSplitter splitter = new MarkdownHeaderParentTextSplitter(2, false, false, 800, 0);
    List<TextSegment> chunks = splitter.split(Document.from(markdown));

    assertThat(chunks.stream().map(TextSegment::text).toList())
        .anyMatch(text -> text.contains("    public int run()"));
  }
}
