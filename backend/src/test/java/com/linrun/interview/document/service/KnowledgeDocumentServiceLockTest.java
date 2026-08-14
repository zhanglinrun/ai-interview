package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.KnowledgeDocumentServiceImpl;
import static org.assertj.core.api.Assertions.assertThat;

import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("知识库版本向量化并发保护")
class KnowledgeDocumentServiceLockTest {

  @Test
  @DisplayName("手动激活和异步激活使用同一版本级锁命名空间")
  void shouldProtectBothActivationEntrypoints() throws Exception {
    Method byId = KnowledgeDocumentServiceImpl.class.getMethod("activateVersion", Long.class);
    Method byEntity = KnowledgeDocumentServiceImpl.class.getMethod(
        "activateVersion", KnowledgeBaseVersionEntity.class);

    DistributeLock idLock = byId.getAnnotation(DistributeLock.class);
    DistributeLock entityLock = byEntity.getAnnotation(DistributeLock.class);

    assertThat(idLock).isNotNull();
    assertThat(entityLock).isNotNull();
    assertThat(idLock.key()).startsWith("'kb:vectorize:'");
    assertThat(entityLock.key()).startsWith("'kb:vectorize:'");
    assertThat(idLock.leaseTime()).isEqualTo(entityLock.leaseTime());
    assertThat(idLock.leaseTime()).isNegative();
  }
}
