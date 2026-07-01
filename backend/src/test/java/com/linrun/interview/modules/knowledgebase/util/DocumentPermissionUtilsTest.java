package com.linrun.interview.modules.knowledgebase.util;

import com.linrun.interview.modules.knowledgebase.constant.DocumentAccessScope;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentPermissionUtils 测试")
class DocumentPermissionUtilsTest {

  @Nested
  @DisplayName("metadataAccessibleBy")
  class MetadataAccessibleBy {

    @Test
    @DisplayName("PRIVATE 应返回 userId 字符串")
    void privateScopeUsesUserId() {
      KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
      entity.setUserId(42L);
      entity.setAccessibleBy("PRIVATE");

      assertThat(DocumentPermissionUtils.metadataAccessibleBy(entity)).isEqualTo("42");
    }

    @Test
    @DisplayName("PUBLIC 应返回 PUBLIC 标记")
    void publicScopeUsesToken() {
      KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
      entity.setUserId(42L);
      entity.setAccessibleBy("PUBLIC");

      assertThat(DocumentPermissionUtils.metadataAccessibleBy(entity)).isEqualTo("PUBLIC");
    }
  }

  @Nested
  @DisplayName("canAccess")
  class CanAccess {

    @Test
    @DisplayName("PUBLIC 分段对所有用户可读")
    void publicMetadataReadable() {
      assertThat(DocumentPermissionUtils.canAccess("PUBLIC", 1L)).isTrue();
      assertThat(DocumentPermissionUtils.canAccess("PUBLIC", null)).isTrue();
    }

    @Test
    @DisplayName("私有分段仅 owner 可读")
    void privateMetadataOwnerOnly() {
      assertThat(DocumentPermissionUtils.canAccess("99", 99L)).isTrue();
      assertThat(DocumentPermissionUtils.canAccess("99", 1L)).isFalse();
    }
  }

  @Nested
  @DisplayName("isExpired")
  class IsExpired {

    @Test
    @DisplayName("null 表示永不过期")
    void nullNeverExpires() {
      assertThat(DocumentPermissionUtils.isExpired(null)).isFalse();
    }

    @Test
    @DisplayName("过去日期视为过期")
    void pastDateExpired() {
      assertThat(DocumentPermissionUtils.isExpired(LocalDate.now().minusDays(1))).isTrue();
    }

    @Test
    @DisplayName("今天及未来未过期")
    void todayNotExpired() {
      assertThat(DocumentPermissionUtils.isExpired(LocalDate.now())).isFalse();
      assertThat(DocumentPermissionUtils.isExpired(LocalDate.now().plusDays(1))).isFalse();
    }
  }

  @Test
  @DisplayName("putExpireDate 应写入 ISO 日期")
  void putExpireDateWritesMetadata() {
    Map<String, String> metadata = new HashMap<>();
    DocumentPermissionUtils.putExpireDate(metadata, LocalDate.of(2026, 12, 31));
    assertThat(metadata).containsEntry("expireDate", "2026-12-31");
  }

  @Test
  @DisplayName("resolveScope 应解析枚举")
  void resolveScopeParsesEnum() {
    assertThat(DocumentPermissionUtils.resolveScope("public")).isEqualTo(DocumentAccessScope.PUBLIC);
    assertThat(DocumentPermissionUtils.resolveScope(null)).isEqualTo(DocumentAccessScope.PRIVATE);
  }
}
