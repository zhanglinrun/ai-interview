package com.linrun.interview.business.listener;

import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.infra.redis.RedisService;
import com.linrun.interview.business.vo.ResumeAnalysisResponse;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import com.linrun.interview.business.entity.ResumeEntity;
import com.linrun.interview.business.service.ResumeGradingService;
import com.linrun.interview.business.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeEntityMapper resumeEntityMapper;

    public AnalyzeStreamConsumer(
        RedisService redisService,
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeEntityMapper resumeEntityMapper
    ) {
        super(redisService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeEntityMapper = resumeEntityMapper;
    }

    record AnalyzePayload(Long resumeId, Long userId) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String userIdStr = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);
        if (resumeIdStr == null || userIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        try {
            return new AnalyzePayload(Long.parseLong(resumeIdStr), Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            log.warn("消息标识格式错误，跳过: messageId={}", messageId);
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId() + ", userId=" + payload.userId();
    }

    @Override
    protected boolean shouldSkip(AnalyzePayload payload) {
        ResumeEntity resume = resumeEntityMapper.selectById(payload.resumeId());
        return resume == null
            || !payload.userId().equals(resume.getUserId())
            || resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED;
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload, AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        ResumeEntity resume = resumeEntityMapper.selectById(resumeId);
        if (resume == null || !payload.userId().equals(resume.getUserId())) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }
        String content = resume.getResumeText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("简历正文不存在，无法异步分析");
        }
        // userId 来自持久化消息并与实体交叉校验，异步线程不读取 UserContext。
        ResumeAnalysisResponse analysis =
            gradingService.analyzeResume(content, payload.userId());
        ResumeEntity latest = resumeEntityMapper.selectById(resumeId);
        if (latest == null || !payload.userId().equals(latest.getUserId())) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(latest, analysis);
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload, AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload, AsyncTaskStatus.FAILED, error);
    }

    private void updateAnalyzeStatus(AnalyzePayload payload, AsyncTaskStatus status, String error) {
        Long resumeId = payload.resumeId();
        try {
            Optional.ofNullable(resumeEntityMapper.selectById(resumeId)).ifPresent(resume -> {
                if (!payload.userId().equals(resume.getUserId())) {
                    log.warn("拒绝更新其他用户的简历状态: resumeId={}, messageUserId={}",
                        resumeId, payload.userId());
                    return;
                }
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(error);
                MapperUtils.save(resumeEntityMapper, resume);
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.getMessage(), e);
        }
    }
}
