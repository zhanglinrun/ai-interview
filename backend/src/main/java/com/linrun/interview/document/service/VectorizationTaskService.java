package com.linrun.interview.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.document.config.VectorizationTaskProperties;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.mapper.KnowledgeBaseVersionMapper;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 持久化的向量化任务租约与有界重试。 */
@Service
@RequiredArgsConstructor
public class VectorizationTaskService {

  private final KnowledgeBaseVersionMapper versionMapper;
  private final VectorizationTaskProperties properties;

  @Transactional(rollbackFor = Exception.class)
  public Claim claim(Long versionId) {
    LocalDateTime now = now();
    LocalDateTime expiredBefore = now.minus(properties.getClaimLease());
    int maxAttempts = Math.max(properties.getMaxAttempts(), 1);
    if (versionMapper.claimEmbedding(versionId, now, expiredBefore, maxAttempts) > 0) {
      return new Claim(ClaimState.ACQUIRED, versionMapper.selectById(versionId));
    }
    KnowledgeBaseVersionEntity current = versionMapper.selectById(versionId);
    if (current == null || current.getStatus() == DocumentStatus.VECTOR_STORED) {
      return new Claim(ClaimState.TERMINAL, current);
    }
    if (Boolean.TRUE.equals(current.getEmbeddingTerminalFailure())
        || value(current.getEmbeddingAttempt()) >= maxAttempts) {
      return new Claim(ClaimState.FAILED, current);
    }
    if (current.getEmbeddingNextRetryAt() != null
        && current.getEmbeddingNextRetryAt().isAfter(now)) {
      return new Claim(ClaimState.BACKOFF, current);
    }
    return new Claim(ClaimState.LEASE_HELD, current);
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean complete(KnowledgeBaseVersionEntity claimed) {
    return versionMapper.completeEmbedding(
        claimed.getVersionId(), value(claimed.getEmbeddingAttempt()), claimed.getEmbeddingClaimedAt()) > 0;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean renew(KnowledgeBaseVersionEntity claimed) {
    LocalDateTime now = now();
    int affected = versionMapper.renewEmbeddingLease(
        claimed.getVersionId(), value(claimed.getEmbeddingAttempt()),
        claimed.getEmbeddingClaimedAt(), now);
    if (affected > 0) {
      claimed.setEmbeddingClaimedAt(now);
      return true;
    }
    return false;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean fail(KnowledgeBaseVersionEntity claimed, Throwable failure) {
    int attempt = Math.max(value(claimed.getEmbeddingAttempt()), 1);
    int maxAttempts = Math.max(properties.getMaxAttempts(), 1);
    boolean terminal = attempt >= maxAttempts;
    LocalDateTime nextRetryAt = terminal ? null : now().plus(backoff(attempt));
    return versionMapper.failEmbedding(
        claimed.getVersionId(), attempt, claimed.getEmbeddingClaimedAt(), nextRetryAt,
        truncate(failure == null ? null : failure.getMessage(), 1000), terminal) > 0;
  }

  public List<KnowledgeBaseVersionEntity> findRecoverable() {
    LocalDateTime now = now();
    LocalDateTime expiredBefore = now.minus(properties.getClaimLease());
    int batchSize = Math.max(1, Math.min(properties.getRecoveryBatchSize(), 200));
    return versionMapper.selectList(
        Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
            .eq(KnowledgeBaseVersionEntity::getStatus, DocumentStatus.CHUNKED)
            .eq(KnowledgeBaseVersionEntity::getEmbeddingTerminalFailure, false)
            .lt(KnowledgeBaseVersionEntity::getEmbeddingAttempt,
                Math.max(properties.getMaxAttempts(), 1))
            .and(query -> query.isNull(KnowledgeBaseVersionEntity::getEmbeddingNextRetryAt)
                .or().le(KnowledgeBaseVersionEntity::getEmbeddingNextRetryAt, now))
            .and(query -> query.isNull(KnowledgeBaseVersionEntity::getEmbeddingClaimedAt)
                .or().lt(KnowledgeBaseVersionEntity::getEmbeddingClaimedAt, expiredBefore))
            .orderByAsc(KnowledgeBaseVersionEntity::getUpdatedAt)
            .last("LIMIT " + batchSize));
  }

  @Transactional(rollbackFor = Exception.class)
  public void reset(Long versionId) {
    versionMapper.resetEmbedding(versionId);
  }

  private Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration calculated;
    try {
      calculated = properties.getBaseBackoff().multipliedBy(multiplier);
    } catch (ArithmeticException overflow) {
      calculated = properties.getMaxBackoff();
    }
    return calculated.compareTo(properties.getMaxBackoff()) > 0
        ? properties.getMaxBackoff() : calculated;
  }

  private LocalDateTime now() {
    return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return "未知向量化错误";
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
  }

  public enum ClaimState {
    ACQUIRED,
    LEASE_HELD,
    BACKOFF,
    FAILED,
    TERMINAL
  }

  public record Claim(ClaimState state, KnowledgeBaseVersionEntity version) {
  }
}
