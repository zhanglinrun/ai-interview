package com.linrun.interview.document.vo;

import com.linrun.interview.document.constant.SegmentStatus;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库分段 DTO")
class KnowledgeBaseSegmentDTOTest {

  @Test
  @DisplayName("父块预览只保留首行和字数，避免和第一子块看起来重复")
  void parentPreviewDoesNotCloneFullPrefix() {
    KnowledgeBaseSegmentEntity entity = new KnowledgeBaseSegmentEntity();
    entity.setId(1L);
    entity.setChunkId("parent-1");
    entity.setText("## 二、对撞指针（左右指针）\n### 核心思路\n" + "内容。".repeat(80));
    entity.setSkipEmbedding(1);
    entity.setStatus(SegmentStatus.STORED);
    entity.setChunkOrder(2);

    KnowledgeBaseSegmentDTO dto = KnowledgeBaseSegmentDTO.from(entity);

    assertThat(dto.skipEmbedding()).isEqualTo(1);
    assertThat(dto.textLength()).isGreaterThan(240);
    assertThat(dto.textPreview()).startsWith("## 二、对撞指针（左右指针）");
    assertThat(dto.textPreview()).contains("父分段全文");
    assertThat(dto.textPreview()).doesNotContain("### 核心思路");
  }

  @Test
  @DisplayName("普通子块仍截断正文预览")
  void childPreviewTruncatesBody() {
    String body = "### 核心思路\n" + "从两端向中间夹。".repeat(40);
    String preview = KnowledgeBaseSegmentDTO.previewOf(body, 0, body.length());

    assertThat(preview).startsWith("### 核心思路");
    assertThat(preview).endsWith("…");
    assertThat(preview.length()).isLessThan(body.length());
  }
}
