package com.linrun.interview.modules.resume.listener;

import com.linrun.interview.common.async.AbstractStreamProducer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {

  private final ResumeEntityMapper resumeEntityMapper;

  record AnalyzeTaskPayload(Long resumeId, String content) {}

  public AnalyzeStreamProducer(RedisService redisService, ResumeEntityMapper resumeEntityMapper) {
    super(redisService);
    this.resumeEntityMapper = resumeEntityMapper;
  }

  public void sendAnalyzeTask(Long resumeId, String content) {
    sendTask(new AnalyzeTaskPayload(resumeId, content));
  }

  @Override
  protected String taskDisplayName() {
    return "分析";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
        AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
    );
  }

  @Override
  protected String payloadIdentifier(AnalyzeTaskPayload payload) {
    return "resumeId=" + payload.resumeId();
  }

  @Override
  protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
    updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error));
  }

  private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
    Optional.ofNullable(resumeEntityMapper.selectById(resumeId)).ifPresent(resume -> {
      resume.setAnalyzeStatus(status);
      if (error != null) {
        resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
      }
      MapperUtils.save(resumeEntityMapper, resume);
    });
  }
}
