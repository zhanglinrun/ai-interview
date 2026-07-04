package com.linrun.interview.modules.knowledgebase.model;

import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;

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
    Integer brotherChunkIndex
) {
  private static final int PREVIEW_MAX = 240;

  public static KnowledgeBaseSegmentDTO from(KnowledgeBaseSegmentEntity entity) {
    String text = entity.getText();
    String preview = text == null ? ""
        : (text.length() <= PREVIEW_MAX ? text : text.substring(0, PREVIEW_MAX) + "…");
    return new KnowledgeBaseSegmentDTO(
        entity.getId(),
        entity.getChunkId(),
        preview,
        entity.getDocumentId(),
        entity.getDocumentVersion(),
        entity.getChunkOrder(),
        entity.getStatus(),
        entity.getParentChunkId(),
        entity.getBrotherChunkId(),
        entity.getBrotherChunkIndex());
  }
}
