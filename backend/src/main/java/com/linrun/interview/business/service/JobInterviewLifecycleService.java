package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 空闲暂停与 24 小时恢复窗口收敛，不依赖请求线程 UserContext。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobInterviewLifecycleService {

  private static final String LEASE_EXPIRED_CODE = "COMMAND_LEASE_EXPIRED";
  private static final String LEASE_EXPIRED_DETAIL =
      "指令执行租约已超时，可能因服务重启中断，请重新提交";

  private final JobInterviewSessionMapper sessionMapper;
  private final InterviewCommandMapper commandMapper;
  private final InterviewSessionEventMapper eventMapper;
  private final JobInterviewSessionPersistenceService sessionPersistence;
  private final JobInterviewProperties properties;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public JobInterviewSessionEntity reconcileOwned(Long userId, String sessionId) {
    JobInterviewSessionEntity session = sessionPersistence.requireOwned(userId, sessionId);
    reconcile(session, LocalDateTime.now());
    return sessionPersistence.requireOwned(userId, sessionId);
  }

  @Transactional(rollbackFor = Exception.class)
  public int reconcileCandidates() {
    LocalDateTime now = LocalDateTime.now();
    List<JobInterviewSessionEntity> candidates = sessionMapper.selectList(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .isNotNull(JobInterviewSessionEntity::getPreparationRunId)
            .in(JobInterviewSessionEntity::getStatus,
                JobInterviewSessionStatus.READY,
                JobInterviewSessionStatus.IN_PROGRESS,
                JobInterviewSessionStatus.PAUSED)
            .and(candidate -> candidate
                .isNotNull(JobInterviewSessionEntity::getActiveCommandId)
                .or()
                .in(JobInterviewSessionEntity::getStatus,
                    JobInterviewSessionStatus.IN_PROGRESS,
                    JobInterviewSessionStatus.PAUSED))
            .orderByAsc(JobInterviewSessionEntity::getLastActivityAt)
            .last("LIMIT 100"));
    int changed = 0;
    for (JobInterviewSessionEntity session : candidates) {
      changed += reconcile(session, now) ? 1 : 0;
    }
    return changed;
  }

  private boolean reconcile(JobInterviewSessionEntity session, LocalDateTime now) {
    boolean changed = reclaimStaleCommand(session, now);
    if (session.getActiveCommandId() != null) {
      return changed;
    }
    if (session.getStatus() == JobInterviewSessionStatus.IN_PROGRESS
        && session.getLastActivityAt() != null
        && !session.getLastActivityAt().plusMinutes(properties.getIdlePauseMinutes()).isAfter(now)) {
      return transition(
          session, JobInterviewSessionStatus.IN_PROGRESS, JobInterviewSessionStatus.PAUSED,
          "SESSION_PAUSED", now, now.plusHours(properties.getResumeHours()));
    }
    if (session.getStatus() == JobInterviewSessionStatus.PAUSED
        && session.getResumeExpiresAt() != null
        && !session.getResumeExpiresAt().isAfter(now)) {
      return transition(
          session, JobInterviewSessionStatus.PAUSED, JobInterviewSessionStatus.ABORTED,
          "SESSION_ABORTED", now, null);
    }
    return changed;
  }

  /**
   * 回收进程宕机后遗留的 PROCESSING 指令。先 CAS 释放会话槽，再 CAS 标记指令失败，
   * 与正常完成路径保持相同的加锁顺序；事务中任一步异常都会回滚。会话版本不前进，
   * 因为过期指令尚未提交任何业务状态，客户端可用新 commandId 在原版本安全重试。
   */
  private boolean reclaimStaleCommand(
      JobInterviewSessionEntity session,
      LocalDateTime now
  ) {
    String activeCommandId = session.getActiveCommandId();
    if (activeCommandId == null || activeCommandId.isBlank()) {
      return false;
    }
    InterviewCommandEntity command = commandMapper.selectOne(
        Wrappers.<InterviewCommandEntity>lambdaQuery()
            .eq(InterviewCommandEntity::getUserId, session.getUserId())
            .eq(InterviewCommandEntity::getSessionId, session.getSessionId())
            .eq(InterviewCommandEntity::getCommandId, activeCommandId));
    LocalDateTime staleBefore = now.minusSeconds(
        Math.max(1, properties.getCommandLeaseSeconds()));
    if (command == null
        || command.getStatus() != InterviewCommandStatus.PROCESSING
        || command.getUpdatedAt() == null
        || command.getUpdatedAt().isAfter(staleBefore)) {
      return false;
    }
    long expectedVersion = command.getExpectedSessionVersion() == null
        ? -1L : command.getExpectedSessionVersion();
    if (expectedVersion < 0
        || session.getSessionVersion() == null
        || session.getSessionVersion() != expectedVersion) {
      return false;
    }
    int released = sessionMapper.releaseCommand(
        session.getId(), session.getUserId(), expectedVersion, activeCommandId);
    if (released != 1) {
      return false;
    }
    int failed = commandMapper.failStaleProcessingCommand(
        command.getId(), command.getUserId(), command.getSessionId(), command.getCommandId(),
        expectedVersion, staleBefore, LEASE_EXPIRED_CODE, LEASE_EXPIRED_DETAIL, now);
    if (failed != 1) {
      InterviewCommandEntity current = commandMapper.selectById(command.getId());
      if (current == null || current.getStatus() == InterviewCommandStatus.PROCESSING) {
        throw new BusinessException(
            ErrorCode.INTERNAL_ERROR, "回收过期岗位实战指令失败");
      }
    }
    session.setActiveCommandId(null);
    log.warn("岗位实战过期指令已回收: sessionId={}, commandId={}, version={}",
        session.getSessionId(), activeCommandId, expectedVersion);
    return true;
  }

  private boolean transition(
      JobInterviewSessionEntity session,
      JobInterviewSessionStatus expectedStatus,
      JobInterviewSessionStatus targetStatus,
      String eventType,
      LocalDateTime now,
      LocalDateTime resumeExpiresAt
  ) {
    long currentVersion = session.getSessionVersion() == null ? 0L : session.getSessionVersion();
    long nextVersion = currentVersion + 1L;
    var update = Wrappers.<JobInterviewSessionEntity>lambdaUpdate()
        .eq(JobInterviewSessionEntity::getId, session.getId())
        .eq(JobInterviewSessionEntity::getUserId, session.getUserId())
        .eq(JobInterviewSessionEntity::getStatus, expectedStatus)
        .eq(JobInterviewSessionEntity::getSessionVersion, currentVersion)
        .isNull(JobInterviewSessionEntity::getActiveCommandId)
        .set(JobInterviewSessionEntity::getStatus, targetStatus)
        .set(JobInterviewSessionEntity::getSessionVersion, nextVersion)
        .set(JobInterviewSessionEntity::getLastActivityAt, now);
    if (targetStatus == JobInterviewSessionStatus.PAUSED) {
      update.set(JobInterviewSessionEntity::getPausedAt, now)
          .set(JobInterviewSessionEntity::getResumeExpiresAt, resumeExpiresAt);
    } else {
      update.set(JobInterviewSessionEntity::getAbortedAt, now)
          .set(JobInterviewSessionEntity::getPausedAt, null)
          .set(JobInterviewSessionEntity::getResumeExpiresAt, null);
    }
    if (sessionMapper.update(null, update) != 1) {
      return false;
    }
    InterviewSessionEventEntity event = InterviewSessionEventEntity.builder()
        .userId(session.getUserId())
        .sessionId(session.getSessionId())
        .eventType(eventType)
        .sessionVersion(nextVersion)
        .payloadJson(writeJson(Map.of(
            "status", targetStatus.name(),
            "reason", targetStatus == JobInterviewSessionStatus.PAUSED
                ? "IDLE_TIMEOUT" : "RESUME_WINDOW_EXPIRED")))
        .createdAt(now)
        .build();
    eventMapper.insert(event);
    log.info("岗位实战生命周期推进: sessionId={}, from={}, to={}, version={}",
        session.getSessionId(), expectedStatus, targetStatus, nextVersion);
    return true;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "序列化岗位实战生命周期事件失败", e);
    }
  }
}
