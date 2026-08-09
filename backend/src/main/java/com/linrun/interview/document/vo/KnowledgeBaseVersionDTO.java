package com.linrun.interview.document.vo;

import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.constant.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 知识库版本 DTO（供前端版本列表展示，对齐 AGENTS.md「禁止直接返回 Entity」）。
 *
 * <p>只暴露前端需要的字段，隐藏 {@code convertedContent}/{@code contentHash}/{@code docUrl} 等内部/敏感字段。
 *
 * @param versionId  版本 ID
 * @param version    语义化版本号（如 1.0.0）
 * @param status     版本状态机
 * @param uploadUser 上传用户标识
 * @param changelog  版本变更说明
 * @param createdAt  创建时间
 */
public record KnowledgeBaseVersionDTO(
    Long versionId,
    String version,
    DocumentStatus status,
    String uploadUser,
    String changelog,
    LocalDateTime createdAt
) {
    public static KnowledgeBaseVersionDTO from(KnowledgeBaseVersionEntity entity) {
        return new KnowledgeBaseVersionDTO(
            entity.getVersionId(),
            entity.getVersion(),
            entity.getStatus(),
            entity.getUploadUser(),
            entity.getChangelog(),
            entity.getCreatedAt()
        );
    }
}
