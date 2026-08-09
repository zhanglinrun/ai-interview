package com.linrun.interview.document.vo;

import com.linrun.interview.document.constant.DocumentParseTaskStatus;
import com.linrun.interview.document.entity.DocumentParseTaskEntity;
import java.time.LocalDateTime;

/** 前端可见的解析状态；不暴露 providerTaskId、对象 key、文件名或内部错误正文。 */
public record DocumentParseTaskDTO(
    Long documentId,
    Long versionId,
    DocumentParseTaskStatus status,
    Integer attempt,
    String failureCode,
    Boolean fallbackUsed,
    String fallbackReason,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {
  public static DocumentParseTaskDTO from(DocumentParseTaskEntity entity) {
    return new DocumentParseTaskDTO(
        entity.getDocumentId(),
        entity.getVersionId(),
        entity.getStatus(),
        entity.getAttempt(),
        entity.getFailureCode(),
        entity.getFallbackUsed(),
        entity.getFallbackReason(),
        entity.getStartedAt(),
        entity.getCompletedAt());
  }
}
