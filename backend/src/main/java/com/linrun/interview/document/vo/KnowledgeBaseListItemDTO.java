package com.linrun.interview.document.vo;

import com.linrun.interview.document.constant.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 知识库列表项DTO
 * 使用MapStruct进行转换，见KnowledgeBaseMapper
 */
public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    DocumentStatus docStatus,
    Long currentVersionId,
    String accessibleBy,
    java.time.LocalDate expireDate,
    /** 当前用户是否为文档所有者（false=他人公开文档，只读） */
    boolean owned
) {
}
