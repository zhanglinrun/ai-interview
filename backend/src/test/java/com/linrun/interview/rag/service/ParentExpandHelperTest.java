package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("父子扩展文本构建测试")
class ParentExpandHelperTest {

  @Test
  @DisplayName("replace 策略应使用父块文本")
  void replaceUsesParentText() {
    Metadata meta = Metadata.from(Map.of(
        MetadataKeyConstant.PARENT_CHUNK_ID, "parent-1",
        MetadataKeyConstant.CHUNK_ID, "child-1"));
    String expanded = ParentExpandHelper.buildExpandedText(
        "child hit",
        meta,
        Map.of("parent-1", "parent full chapter"),
        Map.of(),
        500,
        3,
        "replace");

    assertThat(expanded).isEqualTo("parent full chapter");
  }

  @Test
  @DisplayName("append 策略应保留命中并拼接父块")
  void appendKeepsHitAndParent() {
    Metadata meta = Metadata.from(Map.of(
        MetadataKeyConstant.PARENT_CHUNK_ID, "parent-1",
        MetadataKeyConstant.CHUNK_ID, "child-1"));
    String expanded = ParentExpandHelper.buildExpandedText(
        "child hit",
        meta,
        Map.of("parent-1", "parent chapter"),
        Map.of(),
        500,
        3,
        "append");

    assertThat(expanded).contains("child hit").contains("parent chapter");
  }

  @Test
  @DisplayName("append 策略应拼接兄弟块")
  void appendIncludesBrothers() {
    Metadata meta = Metadata.from(Map.of(
        MetadataKeyConstant.BROTHER_CHUNK_ID, "group-1",
        MetadataKeyConstant.CHUNK_ID, "b1",
        MetadataKeyConstant.BROTHER_CHUNK_INDEX, 0));
    KnowledgeBaseSegmentEntity hit = new KnowledgeBaseSegmentEntity();
    hit.setChunkId("b1");
    hit.setBrotherChunkId("group-1");
    hit.setBrotherChunkIndex(0);
    hit.setText("hit");
    KnowledgeBaseSegmentEntity brother = new KnowledgeBaseSegmentEntity();
    brother.setChunkId("b2");
    brother.setBrotherChunkId("group-1");
    brother.setBrotherChunkIndex(1);
    brother.setText("brother text");

    String expanded = ParentExpandHelper.buildExpandedText(
        "hit",
        meta,
        Map.of(),
        Map.of("group-1", List.of(hit, brother)),
        500,
        3,
        "append");

    assertThat(expanded).contains("brother text");
  }
}
