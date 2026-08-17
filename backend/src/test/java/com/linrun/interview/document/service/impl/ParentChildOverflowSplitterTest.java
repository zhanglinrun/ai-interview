package com.linrun.interview.document.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("超长父章节二次切分")
class ParentChildOverflowSplitterTest {

  @Test
  @DisplayName("子块长度约为父阈值的 40%，避免第一子块复制整节")
  void childChunkSizeIsSmallerThanParent() {
    assertThat(ParentChildOverflowSplitter.childChunkSize(800)).isEqualTo(320);
    assertThat(ParentChildOverflowSplitter.childChunkSize(80)).isEqualTo(32);
  }

  @Test
  @DisplayName("超长二级标题章按三级标题切子块，而不是整章前缀")
  void splitsLongChapterByDeeperHeadings() {
    String content = """
        ## 二、对撞指针（左右指针）
        ### 核心思路
        %s
        ### 适用条件
        %s
        ### 标准模板
        %s
        """.formatted("思路说明。".repeat(20), "适用说明。".repeat(20), "模板说明。".repeat(20));

    List<String> children = ParentChildOverflowSplitter.splitChildren(content, 80, 10);

    assertThat(children).hasSizeGreaterThanOrEqualTo(3);
    assertThat(children.get(0)).contains("### 核心思路").doesNotContain("### 适用条件");
    assertThat(children.stream().anyMatch(part -> part.contains("### 适用条件"))).isTrue();
    assertThat(children.stream().anyMatch(part -> part.contains("### 标准模板"))).isTrue();
    assertThat(children).noneMatch(part ->
        part.contains("### 核心思路") && part.contains("### 适用条件") && part.contains("### 标准模板"));
  }

  @Test
  @DisplayName("仅有标题的章首并入第一个子节，避免空壳块")
  void mergesHeadingOnlyPreambleIntoFirstChild() {
    String content = """
        ## 二、对撞指针
        ### 核心思路
        %s
        ### 适用条件
        %s
        """.formatted("左右夹逼。".repeat(30), "有序数组。".repeat(30));

    List<String> children = ParentChildOverflowSplitter.splitChildren(content, 80, 0);

    assertThat(children.get(0)).startsWith("## 二、对撞指针");
    assertThat(children.get(0)).contains("### 核心思路");
    assertThat(children.get(0)).doesNotContain("### 适用条件");
  }

  @Test
  @DisplayName("没有更细标题时按段落边界切，不从句子中间切开")
  void fallsBackToParagraphBoundary() {
    String paragraph = "第一段内容讲窗口如何收缩。\n\n";
    String content = "## 长章\n" + paragraph.repeat(20) + "最后一段收尾。";

    List<String> children = ParentChildOverflowSplitter.splitChildren(content, 90, 0);

    assertThat(children).hasSizeGreaterThan(1);
    assertThat(children.get(0)).doesNotEndWith("如");
    assertThat(children.get(0).trim()).endsWith("。");
  }

  @Test
  @DisplayName("代码块尽量保持完整，续块带上标题面包屑")
  void keepsCodeFenceIntactAndAddsBreadcrumb() {
    String code = """
        ```java
        public int twoSum(int[] nums, int target) {
            int left = 0;
            int right = nums.length - 1;
            while (left < right) {
                int sum = nums[left] + nums[right];
                if (sum == target) {
                    return left;
                }
                if (sum < target) {
                    left++;
                } else {
                    right--;
                }
            }
            return -1;
        }
        ```
        """;
    String content = "## 标准模板\n说明文字。\n" + code;

    List<String> children = ParentChildOverflowSplitter.splitChildren(content, 80, 10);

    assertThat(children.stream().anyMatch(part ->
        part.contains("```java") && countFences(part) >= 2 && countFences(part) % 2 == 0))
        .isTrue();
    assertThat(children.get(0)).doesNotContain("while (left < right)");
  }

  private static long countFences(String part) {
    return part.lines().filter(line -> line.trim().startsWith("```")).count();
  }
}
