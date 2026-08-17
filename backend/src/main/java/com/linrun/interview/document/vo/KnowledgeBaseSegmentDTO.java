package com.linrun.interview.document.vo;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.document.controller.KnowledgeSegmentController;


import com.linrun.interview.document.constant.SegmentStatus;

/**
 * 知识库分段列表项 DTO（对齐业界实践 KnowledgeSegmentController，禁止直接返回 Entity）。
 */
public record KnowledgeBaseSegmentDTO(
    Long id,
    String chunkId,
    String textPreview,
    Long documentId,
    Long documentVersion,
    Integer chunkOrder,
    SegmentStatus status,
    String parentChunkId,
    String brotherChunkId,
    Integer brotherChunkIndex,
    Integer skipEmbedding,
    Integer textLength
) {
  private static final int PREVIEW_MAX = 240;

  public static KnowledgeBaseSegmentDTO from(KnowledgeBaseSegmentEntity entity) {
    String text = entity.getText();
    int length = text == null ? 0 : text.length();
    Integer skip = entity.getSkipEmbedding();
    return new KnowledgeBaseSegmentDTO(
        entity.getId(),
        entity.getChunkId(),
        previewOf(text, skip, length),
        entity.getDocumentId(),
        entity.getDocumentVersion(),
        entity.getChunkOrder(),
        entity.getStatus(),
        entity.getParentChunkId(),
        entity.getBrotherChunkId(),
        entity.getBrotherChunkIndex(),
        skip == null ? 0 : skip,
        length);
  }

  static String previewOf(String text, Integer skipEmbedding, int length) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    if (skipEmbedding != null && skipEmbedding == 1) {
      return firstContentLine(text) + "\n… 父分段全文 · " + length + " 字，不入库向量";
    }
    return length <= PREVIEW_MAX ? text : text.substring(0, PREVIEW_MAX) + "…";
  }

  private static String firstContentLine(String text) {
    for (String line : text.split("\n", -1)) {
      if (!line.isBlank()) {
        return line.trim();
      }
    }
    return "父块";
  }
}
