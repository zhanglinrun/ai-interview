package com.linrun.interview.document.vo;

/** 文档解析的显式上下文；异步、补偿与外部调用均不依赖 UserContext。 */
public record DocumentParseRequest(
    Long userId,
    Long documentId,
    Long versionId,
    byte[] fileBytes,
    String fileName,
    String contentType,
    String storageKey
) {

  public DocumentParseRequest {
    if (fileBytes == null || fileBytes.length == 0) {
      throw new IllegalArgumentException("fileBytes 不能为空");
    }
    if (fileName == null || fileName.isBlank()) {
      fileName = "unknown";
    }
  }

  public boolean persistentContextAvailable() {
    return userId != null && userId > 0 && documentId != null && versionId != null;
  }
}
