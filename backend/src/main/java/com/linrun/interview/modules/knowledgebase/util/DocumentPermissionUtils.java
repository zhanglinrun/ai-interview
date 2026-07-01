package com.linrun.interview.modules.knowledgebase.util;

import com.linrun.interview.modules.knowledgebase.constant.DocumentAccessScope;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;

import java.time.LocalDate;
import java.util.Map;

/**
 * 文档访问权限工具（对齐 know-engine DocumentPermissionUtils）。
 */
public final class DocumentPermissionUtils {

  public static final String PUBLIC_TOKEN = "PUBLIC";

  private DocumentPermissionUtils() {
  }

  /** 写入 ES / segment metadata 的 accessibleBy 值。 */
  public static String metadataAccessibleBy(KnowledgeBaseEntity entity) {
    if (entity == null || entity.getUserId() == null) {
      return PUBLIC_TOKEN;
    }
    DocumentAccessScope scope = resolveScope(entity.getAccessibleBy());
    return scope == DocumentAccessScope.PUBLIC ? PUBLIC_TOKEN : String.valueOf(entity.getUserId());
  }

  public static DocumentAccessScope resolveScope(String raw) {
    return DocumentAccessScope.from(raw);
  }

  /** 当前用户是否可读该 metadata 标记的分段。 */
  public static boolean canAccess(String metadataAccessibleBy, Long userId) {
    if (metadataAccessibleBy == null || metadataAccessibleBy.isBlank()) {
      return true;
    }
    if (PUBLIC_TOKEN.equalsIgnoreCase(metadataAccessibleBy)) {
      return true;
    }
    return userId != null && metadataAccessibleBy.equals(String.valueOf(userId));
  }

  /** 文档是否已过期（null 表示永不过期）。 */
  public static boolean isExpired(LocalDate expireDate) {
    return expireDate != null && expireDate.isBefore(LocalDate.now());
  }

  /** 切块时写入 metadata 的到期日（ISO-8601 或空）。 */
  public static void putExpireDate(Map<String, String> metadata, LocalDate expireDate) {
    if (expireDate != null) {
      metadata.put(MetadataKeyConstant.EXPIRE_DATE, expireDate.toString());
    }
  }
}
