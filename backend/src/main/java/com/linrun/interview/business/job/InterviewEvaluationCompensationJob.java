package com.linrun.interview.business.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.business.listener.EvaluateStreamProducer;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.EvaluationQuality;
import com.linrun.interview.business.service.InterviewPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试评估补偿：PENDING 丢失、FAILED、以及降级 0 分报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewEvaluationCompensationJob {

    private static final int STALE_MINUTES = 10;
    private static final int BATCH_LIMIT = 50;

    private final InterviewSessionMapper sessionMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final InterviewPersistenceService persistenceService;

    @Scheduled(fixedDelayString = "${app.interview.compensation.evaluate-delay-ms:300000}",
        initialDelayString = "${app.interview.compensation.evaluate-initial-delay-ms:90000}")
    @DistributeLock(key = "'interview:compensation:evaluate'", waitTime = 0, leaseTime = 300,
        message = "面试评估补偿任务已在其他实例执行")
    public void runEvaluationCompensation() {
        List<InterviewSessionEntity> staleSessions = sessionMapper.selectList(
            Wrappers.<InterviewSessionEntity>lambdaQuery()
                .lt(InterviewSessionEntity::getCompletedAt, LocalDateTime.now().minusMinutes(STALE_MINUTES))
                .and(w -> w
                    .nested(pending -> pending
                        .eq(InterviewSessionEntity::getStatus, InterviewSessionEntity.SessionStatus.COMPLETED)
                        .and(status -> status
                            .eq(InterviewSessionEntity::getEvaluateStatus, AsyncTaskStatus.PENDING)
                            .or().isNull(InterviewSessionEntity::getEvaluateStatus)))
                    .or().eq(InterviewSessionEntity::getEvaluateStatus, AsyncTaskStatus.FAILED)
                    .or().nested(degraded -> degraded
                        .eq(InterviewSessionEntity::getStatus, InterviewSessionEntity.SessionStatus.EVALUATED)
                        .eq(InterviewSessionEntity::getEvaluateStatus, AsyncTaskStatus.COMPLETED)))
                .last("LIMIT " + BATCH_LIMIT));
        if (staleSessions.isEmpty()) {
            return;
        }
        log.info("发现 {} 个评估补偿候选会话", staleSessions.size());
        int requeued = 0;
        for (InterviewSessionEntity session : staleSessions) {
            try {
                if (!shouldRequeue(session)) {
                    continue;
                }
                int nextAttempt = EvaluationQuality.compensationAttempts(session.getEvaluateError()) + 1;
                if (!EvaluationQuality.canCompensate(nextAttempt - 1)) {
                    log.warn("评估补偿次数已达上限: sessionId={}", session.getSessionId());
                    continue;
                }
                String error = EvaluationQuality.withCompensationAttempt(
                    nextAttempt, session.getEvaluateError());
                if (session.getEvaluateStatus() == AsyncTaskStatus.FAILED
                    || session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
                    persistenceService.prepareReevaluation(session.getSessionId(), error);
                } else if (session.getEvaluateStatus() == null) {
                    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
                    MapperUtils.save(sessionMapper, session);
                }
                evaluateStreamProducer.sendEvaluateTask(session.getSessionId());
                requeued++;
            } catch (Exception e) {
                log.error("评估补偿重派失败: sessionId={}", session.getSessionId(), e);
            }
        }
        log.info("面试评估补偿任务完成: 重派 {}/{}", requeued, staleSessions.size());
    }

    boolean shouldRequeue(InterviewSessionEntity session) {
        if (session.getEvaluateStatus() == AsyncTaskStatus.FAILED) {
            return true;
        }
        if (session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            return EvaluationQuality.isDegradedFeedback(session.getOverallFeedback());
        }
        return session.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
            && (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
            || session.getEvaluateStatus() == null);
    }
}
