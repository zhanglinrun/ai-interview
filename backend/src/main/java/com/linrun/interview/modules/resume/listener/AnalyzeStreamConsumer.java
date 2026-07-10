package com.linrun.interview.modules.resume.listener;

import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.interview.model.ResumeAnalysisResponse;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import com.linrun.interview.modules.resume.service.ResumeGradingService;
import com.linrun.interview.modules.resume.service.ResumePersistenceService;
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

    record AnalyzePayload(Long resumeId, String content) {}

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
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (resumeIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        ResumeEntity resume = resumeEntityMapper.selectById(resumeId);
        if (resume == null) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }
        // 异步简历分析无 UserContext：从简历实体恢复 userId，走该用户的 BYOK「我的模型」
        ResumeAnalysisResponse analysis =
            gradingService.analyzeResume(payload.content(), resume.getUserId());
        ResumeEntity latest = resumeEntityMapper.selectById(resumeId);
        if (latest == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(latest, analysis);
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            Optional.ofNullable(resumeEntityMapper.selectById(resumeId)).ifPresent(resume -> {
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
