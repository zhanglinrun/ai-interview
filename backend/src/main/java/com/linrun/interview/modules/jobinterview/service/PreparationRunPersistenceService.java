package com.linrun.interview.modules.jobinterview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewSessionMapper;
import com.linrun.interview.modules.jobinterview.mapper.PreparationRunMapper;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.PreparationRunEntity;
import com.linrun.interview.modules.jobinterview.model.PreparationStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 准备任务短事务边界；外部检索和 LLM 均在调用方事务外执行。 */
@Service
@RequiredArgsConstructor
public class PreparationRunPersistenceService {

  private final PreparationRunMapper mapper;
  private final JobInterviewSessionMapper sessionMapper;

  @Transactional(rollbackFor = Exception.class)
  public PreparationRunEntity create(PreparationRunEntity run) {
    LocalDateTime now = LocalDateTime.now();
    run.setStatus(PreparationStatus.DRAFT);
    run.setAttempt(0);
    run.setCreatedAt(now);
    run.setUpdatedAt(now);
    mapper.insert(run);
    run.setStatus(PreparationStatus.PREPARING);
    run.setUpdatedAt(LocalDateTime.now());
    mapper.updateById(run);
    return run;
  }

  public Optional<PreparationRunEntity> findReusable(Long userId, String fingerprint) {
    PreparationRunEntity run = mapper.selectOne(
        Wrappers.<PreparationRunEntity>lambdaQuery()
            .eq(PreparationRunEntity::getUserId, userId)
            .eq(PreparationRunEntity::getFingerprint, fingerprint)
            .in(PreparationRunEntity::getStatus,
                PreparationStatus.PREPARING, PreparationStatus.READY)
            .orderByDesc(PreparationRunEntity::getCreatedAt)
            .last("LIMIT 1"));
    if (run == null) {
      return Optional.empty();
    }
    // 准备中的同指纹任务用于吸收重复点击；完成后还必须核验关联会话可续面。
    if (run.getStatus() == PreparationStatus.PREPARING) {
      return Optional.of(run);
    }
    if (run.getSessionId() == null || run.getSessionId().isBlank()) {
      return Optional.empty();
    }
    JobInterviewSessionEntity session = sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getUserId, userId)
            .eq(JobInterviewSessionEntity::getSessionId, run.getSessionId())
            .eq(JobInterviewSessionEntity::getPreparationRunId, run.getRunId()));
    if (session == null || session.getStatus() == null || !session.getStatus().resumable()) {
      return Optional.empty();
    }
    return Optional.of(run);
  }

  public PreparationRunEntity requireOwned(Long userId, String runId) {
    PreparationRunEntity run = mapper.selectOne(
        Wrappers.<PreparationRunEntity>lambdaQuery()
            .eq(PreparationRunEntity::getUserId, userId)
            .eq(PreparationRunEntity::getRunId, runId));
    if (run == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "岗位实战准备任务不存在");
    }
    return run;
  }

  public Optional<PreparationRunEntity> findInternal(String runId) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<PreparationRunEntity>lambdaQuery()
            .eq(PreparationRunEntity::getRunId, runId)));
  }

  @Transactional(rollbackFor = Exception.class)
  public void markProcessing(String runId, Long userId) {
    int updated = mapper.update(null, Wrappers.<PreparationRunEntity>lambdaUpdate()
        .eq(PreparationRunEntity::getRunId, runId)
        .eq(PreparationRunEntity::getUserId, userId)
        .eq(PreparationRunEntity::getStatus, PreparationStatus.PREPARING)
        .setSql("attempt = attempt + 1")
        .set(PreparationRunEntity::getUpdatedAt, LocalDateTime.now()));
    if (updated == 0) {
      PreparationRunEntity run = requireOwned(userId, runId);
      if (run.getStatus() != PreparationStatus.READY) {
        throw new BusinessException(ErrorCode.INTERVIEW_PREPARATION_NOT_READY,
            "准备任务已不处于可处理状态: " + run.getStatus());
      }
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void markReady(
      String runId,
      Long userId,
      String sessionId,
      String planJson,
      String evidenceSnapshotIdsJson,
      String dependencyStatusJson,
      String degradedReasonsJson
  ) {
    int updated = mapper.update(null, Wrappers.<PreparationRunEntity>lambdaUpdate()
        .eq(PreparationRunEntity::getRunId, runId)
        .eq(PreparationRunEntity::getUserId, userId)
        .eq(PreparationRunEntity::getStatus, PreparationStatus.PREPARING)
        .set(PreparationRunEntity::getStatus, PreparationStatus.READY)
        .set(PreparationRunEntity::getSessionId, sessionId)
        .set(PreparationRunEntity::getPlanJson, planJson)
        .set(PreparationRunEntity::getEvidenceSnapshotIdsJson, evidenceSnapshotIdsJson)
        .set(PreparationRunEntity::getDependencyStatusJson, dependencyStatusJson)
        .set(PreparationRunEntity::getDegradedReasonsJson, degradedReasonsJson)
        .set(PreparationRunEntity::getFailureCode, null)
        .set(PreparationRunEntity::getFailureDetail, null)
        .set(PreparationRunEntity::getUpdatedAt, LocalDateTime.now())
        .set(PreparationRunEntity::getCompletedAt, LocalDateTime.now()));
    if (updated == 0) {
      PreparationRunEntity run = requireOwned(userId, runId);
      if (run.getStatus() != PreparationStatus.READY) {
        throw new BusinessException(ErrorCode.INTERVIEW_PREPARATION_NOT_READY,
            "准备任务状态已变化，不能覆盖: " + run.getStatus());
      }
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void markFailed(String runId, Long userId, String code, String detail) {
    mapper.update(null, Wrappers.<PreparationRunEntity>lambdaUpdate()
        .eq(PreparationRunEntity::getRunId, runId)
        .eq(PreparationRunEntity::getUserId, userId)
        .ne(PreparationRunEntity::getStatus, PreparationStatus.READY)
        .set(PreparationRunEntity::getStatus, PreparationStatus.FAILED)
        .set(PreparationRunEntity::getFailureCode, truncate(code, 64))
        .set(PreparationRunEntity::getFailureDetail, truncate(detail, 500))
        .set(PreparationRunEntity::getUpdatedAt, LocalDateTime.now())
        .set(PreparationRunEntity::getCompletedAt, LocalDateTime.now()));
  }

  private String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }
}
