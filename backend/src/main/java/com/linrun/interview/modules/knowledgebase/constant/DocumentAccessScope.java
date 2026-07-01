package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库文档可见范围（对齐 know-engine accessibleBy / RoleEnum 简化版）。
 */
public enum DocumentAccessScope {

  /** 仅上传者本人可检索。 */
  PRIVATE,

  /** 任意已登录用户在选择该知识库时可检索（仍须显式勾选 KB）。 */
  PUBLIC;

  public static DocumentAccessScope from(String raw) {
    if (raw == null || raw.isBlank()) {
      return PRIVATE;
    }
    try {
      return DocumentAccessScope.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return PRIVATE;
    }
  }
}
