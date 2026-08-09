package com.linrun.interview.business.listener;

import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.infra.redis.RedisService;
import com.linrun.interview.business.constant.ReportStatus;
import com.linrun.interview.business.service.ReportGenerationProcessor;
import com.linrun.interview.business.service.ReportGenerationProcessor.ProcessOutcome;
import com.linrun.interview.business.service.ReportPersistenceService;
import java.util.Map;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

@Component
public class ReportTaskConsumer
    extends AbstractStreamConsumer<ReportTaskConsumer.Payload> {

  private final ReportPersistenceService persistenceService;
  private final ReportGenerationProcessor processor;

  public ReportTaskConsumer(
      RedisService redisService,
      ReportPersistenceService persistenceService,
      ReportGenerationProcessor processor
  ) {
    super(redisService);
    this.persistenceService = persistenceService;
    this.processor = processor;
  }

  @Override
  protected String taskDisplayName() {
    return "证据化复盘";
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.INTERVIEW_REPORT_GROUP_NAME;
  }

  @Override
  protected Payload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String reportId = data.get(AsyncTaskStreamConstants.FIELD_REPORT_ID);
    String userId = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);
    if (reportId == null || userId == null) {
      return null;
    }
    try {
      return new Payload(reportId, Long.parseLong(userId));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  protected String payloadIdentifier(Payload payload) {
    return "reportId=" + payload.reportId() + ", userId=" + payload.userId();
  }

  @Override
  protected boolean shouldSkip(Payload payload) {
    return persistenceService.findInternal(payload.reportId())
        .map(report -> !payload.userId().equals(report.getUserId())
            || report.getStatus() != ReportStatus.GENERATING)
        .orElse(true);
  }

  @Override
  protected void markProcessing(Payload payload) {
    // processor 使用数据库 claimGeneration 原子抢占，防止重复消息产生重复 BYOK 调用。
  }

  @Override
  protected boolean processBusinessToCompletion(Payload payload) {
    return processor.process(payload.reportId(), payload.userId()) == ProcessOutcome.FINISHED;
  }

  @Override
  protected void processBusiness(Payload payload) {
    // 由 processBusinessToCompletion 返回租约占用状态，避免把延后任务写成幂等 DONE。
  }

  @Override
  protected void markCompleted(Payload payload) {
    // processor 原子完成报告、证据历史、画像和训练推荐。
  }

  @Override
  protected void markFailed(Payload payload, String error) {
    persistenceService.markFailed(
        payload.reportId(), payload.userId(), "REPORT_PIPELINE_FAILED", error);
  }

  record Payload(String reportId, Long userId) {
  }
}
