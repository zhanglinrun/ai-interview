package com.linrun.interview.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.document.service.impl.VectorizationTaskService;
import com.linrun.interview.document.config.VectorizationTaskProperties;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.mapper.KnowledgeBaseVersionMapper;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("知识库向量化持久化任务")
class VectorizationTaskServiceTest {

  private KnowledgeBaseVersionMapper mapper;
  private VectorizationTaskProperties properties;
  private VectorizationTaskService service;

  @BeforeEach
  void setUp() {
    mapper = mock(KnowledgeBaseVersionMapper.class);
    properties = new VectorizationTaskProperties();
    properties.setMaxAttempts(3);
    properties.setBaseBackoff(Duration.ofMinutes(1));
    properties.setMaxBackoff(Duration.ofMinutes(10));
    service = new VectorizationTaskService(mapper, properties);
  }

  @Test
  @DisplayName("数据库 CAS 抢占成功后返回递增后的尝试次数")
  void shouldClaimWithDatabaseCas() {
    KnowledgeBaseVersionEntity version = version(1);
    when(mapper.claimEmbedding(eq(9L), any(LocalDateTime.class), any(LocalDateTime.class), eq(3)))
        .thenReturn(1);
    when(mapper.selectById(9L)).thenReturn(version);

    VectorizationTaskService.Claim claim = service.claim(9L);

    assertThat(claim.state()).isEqualTo(VectorizationTaskService.ClaimState.ACQUIRED);
    assertThat(claim.version()).isSameAs(version);
  }

  @Test
  @DisplayName("未达到上限时记录指数退避时间")
  void shouldScheduleRetryWithBackoff() {
    KnowledgeBaseVersionEntity version = version(2);
    ArgumentCaptor<LocalDateTime> nextRetry = ArgumentCaptor.forClass(LocalDateTime.class);
    when(mapper.failEmbedding(eq(9L), eq(2), eq(version.getEmbeddingClaimedAt()),
        any(LocalDateTime.class), eq("embedding unavailable"), eq(false))).thenReturn(1);

    assertThat(service.fail(version, new IllegalStateException("embedding unavailable"))).isTrue();

    verify(mapper).failEmbedding(eq(9L), eq(2), eq(version.getEmbeddingClaimedAt()), nextRetry.capture(),
        eq("embedding unavailable"), eq(false));
    assertThat(nextRetry.getValue()).isAfter(LocalDateTime.now());
  }

  @Test
  @DisplayName("达到最大次数后停止自动重试并保留错误")
  void shouldStopAfterMaxAttempts() {
    KnowledgeBaseVersionEntity version = version(3);
    when(mapper.failEmbedding(9L, 3, version.getEmbeddingClaimedAt(),
        null, "permanent failure", true)).thenReturn(1);

    assertThat(service.fail(version, new IllegalStateException("permanent failure"))).isTrue();

    verify(mapper).failEmbedding(9L, 3, version.getEmbeddingClaimedAt(),
        null, "permanent failure", true);
  }

  @Test
  @DisplayName("旧租约完成或失败时不得覆盖新任务状态")
  void staleLeaseCannotCompleteOrFailNewClaim() {
    KnowledgeBaseVersionEntity stale = version(1);
    when(mapper.completeEmbedding(9L, 1, stale.getEmbeddingClaimedAt())).thenReturn(0);
    when(mapper.failEmbedding(eq(9L), eq(1), eq(stale.getEmbeddingClaimedAt()),
        any(LocalDateTime.class), eq("late failure"), eq(false))).thenReturn(0);

    assertThat(service.complete(stale)).isFalse();
    assertThat(service.fail(stale, new IllegalStateException("late failure"))).isFalse();
  }

  @Test
  @DisplayName("续租成功后更新当前 claim token")
  void renewsCurrentClaimToken() {
    KnowledgeBaseVersionEntity claimed = version(1);
    LocalDateTime previous = claimed.getEmbeddingClaimedAt();
    ArgumentCaptor<LocalDateTime> renewedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    when(mapper.renewEmbeddingLease(
        eq(9L), eq(1), eq(previous), any(LocalDateTime.class))).thenReturn(1);

    assertThat(service.renew(claimed)).isTrue();
    assertThat(claimed.getEmbeddingClaimedAt()).isAfter(previous);
    verify(mapper).renewEmbeddingLease(eq(9L), eq(1), eq(previous), renewedAt.capture());
    assertThat(renewedAt.getValue().getNano() % 1_000).isZero();
    assertThat(claimed.getEmbeddingClaimedAt()).isEqualTo(renewedAt.getValue());
  }

  private KnowledgeBaseVersionEntity version(int attempt) {
    KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
    version.setVersionId(9L);
    version.setStatus(DocumentStatus.CHUNKED);
    version.setEmbeddingAttempt(attempt);
    version.setEmbeddingClaimedAt(LocalDateTime.of(2026, 7, 19, 12, 0).plusMinutes(attempt));
    return version;
  }
}
