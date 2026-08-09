package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.client.JudgeClientResult;
import com.linrun.interview.business.mapper.CodingAttemptMapper;
import com.linrun.interview.business.mapper.JudgeSubmissionMapper;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.constant.CodingAttemptStatus;
import com.linrun.interview.business.constant.JudgeStatus;
import com.linrun.interview.business.entity.JudgeSubmissionEntity;
import com.linrun.interview.business.constant.TestSuiteType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只负责短事务状态变化；任何 JudgeClient 调用都发生在本类之外。 */
@Service
@RequiredArgsConstructor
public class CodingSubmissionPersistenceService {

  private static final Duration STALE_SUBMISSION_AFTER = Duration.ofMinutes(2);

  private final JudgeSubmissionMapper submissionMapper;
  private final CodingAttemptMapper attemptMapper;

  @Transactional(rollbackFor = Exception.class)
  public SubmissionReservation reserve(
      CodingAttemptEntity attempt,
      String idempotencyKey,
      TestSuiteType suiteType,
      String sourceCode,
      String codeHash,
      int totalCount,
      String provider
  ) {
    JudgeSubmissionEntity existing = findByIdempotency(
        attempt.getUserId(), attempt.getId(), idempotencyKey);
    if (existing != null) {
      validateSameRequest(existing, suiteType, codeHash);
      return new SubmissionReservation(existing, false);
    }
    LocalDateTime now = LocalDateTime.now();
    JudgeSubmissionEntity entity = JudgeSubmissionEntity.builder()
        .submissionId(UUID.randomUUID().toString())
        .userId(attempt.getUserId())
        .attemptId(attempt.getId())
        .idempotencyKey(idempotencyKey)
        .suiteType(suiteType)
        .language(attempt.getLanguage())
        .sourceCode(sourceCode)
        .codeHash(codeHash)
        .status(JudgeStatus.QUEUED)
        .provider(provider)
        .passedCount(0)
        .totalCount(totalCount)
        .submittedAt(now)
        .createdAt(now)
        .updatedAt(now)
        .lockVersion(0)
        .build();
    try {
      submissionMapper.insert(entity);
    } catch (DuplicateKeyException e) {
      JudgeSubmissionEntity concurrent = findByIdempotency(
          attempt.getUserId(), attempt.getId(), idempotencyKey);
      if (concurrent == null) {
        throw e;
      }
      validateSameRequest(concurrent, suiteType, codeHash);
      return new SubmissionReservation(concurrent, false);
    }
    if (suiteType == TestSuiteType.HIDDEN) {
      attempt.setStatus(CodingAttemptStatus.SUBMITTED);
      attempt.setSubmittedAt(now);
      attempt.setUpdatedAt(now);
      if (attemptMapper.updateById(attempt) == 0) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "算法作答状态已变化，请刷新后重试");
      }
    }
    return new SubmissionReservation(entity, true);
  }

  @Transactional(rollbackFor = Exception.class)
  public JudgeSubmissionEntity complete(Long id, JudgeClientResult result) {
    JudgeSubmissionEntity entity = submissionMapper.selectById(id);
    if (entity == null) {
      throw new BusinessException(ErrorCode.JUDGE_SUBMISSION_NOT_FOUND);
    }
    if (result == null || result.status() == null) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "判题结果为空");
    }
    entity.setProviderSubmissionId(result.providerSubmissionId());
    entity.setStatus(result.status());
    entity.setPassedCount(result.passedCount());
    entity.setTotalCount(result.totalCount());
    entity.setDiagnostic(truncate(result.diagnostic(), 2000));
    entity.setTimeMs(result.timeMs());
    entity.setMemoryKb(result.memoryKb());
    entity.setFailureCode(result.failureCode());
    LocalDateTime now = LocalDateTime.now();
    entity.setUpdatedAt(now);
    if (result.status().terminal()) {
      entity.setCompletedAt(now);
    }
    if (submissionMapper.updateById(entity) == 0) {
      throw new BusinessException(ErrorCode.JUDGE_REJUDGE_NOT_ALLOWED, "判题状态已被并发更新");
    }
    if (entity.getSuiteType() == TestSuiteType.HIDDEN) {
      updateAttemptAfterHiddenJudge(entity, result.status(), now);
    }
    return entity;
  }

  @Transactional(rollbackFor = Exception.class)
  public JudgeSubmissionEntity reserveRejudge(Long userId, String submissionId) {
    JudgeSubmissionEntity entity = requireOwned(userId, submissionId);
    LocalDateTime now = LocalDateTime.now();
    int updated = submissionMapper.reserveRejudge(
        entity.getId(), userId, now.minus(STALE_SUBMISSION_AFTER), now);
    if (updated == 0) {
      throw new BusinessException(ErrorCode.JUDGE_REJUDGE_NOT_ALLOWED);
    }
    return requireOwned(userId, submissionId);
  }

  private void updateAttemptAfterHiddenJudge(
      JudgeSubmissionEntity submission,
      JudgeStatus judgeStatus,
      LocalDateTime now
  ) {
    CodingAttemptStatus attemptStatus;
    LocalDateTime completedAt = null;
    if (judgeStatus == JudgeStatus.ACCEPTED) {
      attemptStatus = CodingAttemptStatus.COMPLETED;
      completedAt = now;
    } else if (judgeStatus.pendingRejudge()) {
      attemptStatus = CodingAttemptStatus.SUBMITTED;
    } else {
      attemptStatus = CodingAttemptStatus.IN_PROGRESS;
    }
    attemptMapper.updateAfterHiddenJudge(
        submission.getAttemptId(), submission.getUserId(), attemptStatus, completedAt, now);
  }

  public JudgeSubmissionEntity requireOwned(Long userId, String submissionId) {
    JudgeSubmissionEntity entity = submissionMapper.selectOne(
        Wrappers.<JudgeSubmissionEntity>lambdaQuery()
            .eq(JudgeSubmissionEntity::getUserId, userId)
            .eq(JudgeSubmissionEntity::getSubmissionId, submissionId));
    if (entity == null) {
      throw new BusinessException(ErrorCode.JUDGE_SUBMISSION_NOT_FOUND);
    }
    return entity;
  }

  private JudgeSubmissionEntity findByIdempotency(
      Long userId,
      Long attemptId,
      String idempotencyKey
  ) {
    return submissionMapper.selectOne(Wrappers.<JudgeSubmissionEntity>lambdaQuery()
        .eq(JudgeSubmissionEntity::getUserId, userId)
        .eq(JudgeSubmissionEntity::getAttemptId, attemptId)
        .eq(JudgeSubmissionEntity::getIdempotencyKey, idempotencyKey));
  }

  private void validateSameRequest(
      JudgeSubmissionEntity existing,
      TestSuiteType suiteType,
      String codeHash
  ) {
    if (existing.getSuiteType() != suiteType || !existing.getCodeHash().equals(codeHash)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "同一幂等键不能用于不同源码或判题类型");
    }
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  public record SubmissionReservation(JudgeSubmissionEntity submission, boolean fresh) {
  }
}
