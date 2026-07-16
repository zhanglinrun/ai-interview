package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.evidence.EvidenceScope;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.infrastructure.mapper.KnowledgeBaseMapper;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.knowledgebase.constant.DocumentAccessScope;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库列表服务")
class KnowledgeBaseListServiceTest {

  private final KnowledgeBaseEntityMapper entityMapper = mock(KnowledgeBaseEntityMapper.class);
  private final RedisService redisService = mock(RedisService.class);
  private final KnowledgeBaseListService service = new KnowledgeBaseListService(
      entityMapper,
      mock(KnowledgeBaseMapper.class),
      mock(FileStorageService.class),
      redisService);

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("不存在的知识库 ID 命中空值缓存后不再打 DB")
  void nullCacheSkipsRepeatedMissingIdLookup() {
    UserContext.setUserId(7L);
    when(redisService.get("kb:null:7:99")).thenReturn(null, true);

    assertThat(service.getKnowledgeBaseEntity(99L)).isEmpty();
    assertThat(service.getKnowledgeBaseEntity(99L)).isEmpty();

    verify(entityMapper, times(1)).selectById(99L);
    verify(redisService).set(eq("kb:null:7:99"), eq(true), any(Duration.class));
  }

  @Test
  @DisplayName("访问者读取他人公开库时使用资源真实 owner 构造证据范围")
  void publicKnowledgeBaseUsesActualOwnerInEvidenceScope() {
    KnowledgeBaseEntity publicKnowledgeBase = knowledgeBase(
        9L, 77L, DocumentAccessScope.PUBLIC);
    when(entityMapper.selectList(any())).thenReturn(List.of(publicKnowledgeBase));

    List<EvidenceScope> scopes = service.resolveReadableCandidateScopes(7L, List.of(9L));

    assertThat(scopes).hasSize(1);
    EvidenceScope scope = scopes.getFirst();
    assertThat(scope.dataUserId()).isEqualTo(77L);
    assertThat(scope.ownerFor(DataDomain.CANDIDATE)).isEqualTo(77L);
    assertThat(scope.domains().getFirst().resourceIds()).containsExactly("9");
  }

  @Test
  @DisplayName("他人私有库即使误入 Mapper 结果也在进入 ES 前拒绝")
  void foreignPrivateKnowledgeBaseIsRejected() {
    KnowledgeBaseEntity privateKnowledgeBase = knowledgeBase(
        9L, 77L, DocumentAccessScope.PRIVATE);
    when(entityMapper.selectList(any())).thenReturn(List.of(privateKnowledgeBase));

    assertThatThrownBy(() -> service.resolveReadableCandidateScopes(7L, List.of(9L)))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("统计中的提问次数与知识库列表累计口径一致")
  void statisticsSumKnowledgeBaseQuestionCount() {
    UserContext.setUserId(7L);
    KnowledgeBaseEntity first = knowledgeBase(1L, 7L, DocumentAccessScope.PRIVATE);
    first.setQuestionCount(3);
    first.setAccessCount(5);
    KnowledgeBaseEntity second = knowledgeBase(2L, 7L, DocumentAccessScope.PRIVATE);
    second.setQuestionCount(4);
    second.setAccessCount(2);
    when(entityMapper.selectList(any())).thenReturn(List.of(first, second));

    KnowledgeBaseStatsDTO statistics = service.getStatistics();

    assertThat(statistics.totalCount()).isEqualTo(2);
    assertThat(statistics.totalQuestionCount()).isEqualTo(7);
    assertThat(statistics.totalAccessCount()).isEqualTo(7);
  }

  private KnowledgeBaseEntity knowledgeBase(
      Long id,
      Long ownerUserId,
      DocumentAccessScope accessScope
  ) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(id);
    entity.setUserId(ownerUserId);
    entity.setAccessibleBy(accessScope.name());
    return entity;
  }
}
