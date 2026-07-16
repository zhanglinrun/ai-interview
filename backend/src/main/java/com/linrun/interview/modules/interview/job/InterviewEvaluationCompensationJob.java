package com.linrun.interview.modules.interview.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.interview.listener.EvaluateStreamProducer;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试评估补偿任务：扫描「已完成但评估任务疑似丢失」的会话，重新入队。
 *
 * <p>覆盖场景：提交/交卷时评估任务入队失败（evaluate_status 停在 PENDING）、
 * 历史数据 evaluate_status 为 null 但会话已 COMPLETED。
 * 幂等性由消费端状态检查保证（已评估的会话消费时直接跳过）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewEvaluationCompensationJob {

    /** 完成超过该分钟数仍无评估进展才补偿，避免与正常入队的任务竞争 */
    private static final int STALE_MINUTES = 10;
    private static final int BATCH_LIMIT = 50;

    private final InterviewSessionMapper sessionMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;

    @Scheduled(fixedDelayString = "${app.interview.compensation.evaluate-delay-ms:300000}",
        initialDelayString = "${app.interview.compensation.evaluate-initial-delay-ms:90000}")
    @DistributeLock(key = "'interview:compensation:evaluate'", waitTime = 0, leaseTime = 300,
        message = "面试评估补偿任务已在其他实例执行")
    public void runEvaluationCompensation() {
        List<InterviewSessionEntity> staleSessions = sessionMapper.selectList(
            Wrappers.<InterviewSessionEntity>lambdaQuery()
                .eq(InterviewSessionEntity::getStatus, InterviewSessionEntity.SessionStatus.COMPLETED)
                .and(w -> w.eq(InterviewSessionEntity::getEvaluateStatus, AsyncTaskStatus.PENDING)
                    .or().isNull(InterviewSessionEntity::getEvaluateStatus))
                .isNull(InterviewSessionEntity::getPreparationRunId)
                .lt(InterviewSessionEntity::getCompletedAt, LocalDateTime.now().minusMinutes(STALE_MINUTES))
                .last("LIMIT " + BATCH_LIMIT));
        if (staleSessions.isEmpty()) {
            return;
        }
        log.info("发现 {} 个评估任务疑似丢失的会话，开始补偿重派", staleSessions.size());
        int requeued = 0;
        for (InterviewSessionEntity session : staleSessions) {
            try {
                if (session.getPreparationRunId() != null) {
                    log.warn("旧评估补偿跳过岗位实战会话: sessionId={}", session.getSessionId());
                    continue;
                }
                if (session.getEvaluateStatus() == null) {
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
}
