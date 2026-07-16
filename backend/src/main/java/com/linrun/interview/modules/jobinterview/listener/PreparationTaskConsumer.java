package com.linrun.interview.modules.jobinterview.listener;

import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.jobinterview.model.PreparationStatus;
import com.linrun.interview.modules.jobinterview.service.JobInterviewPreparationProcessor;
import com.linrun.interview.modules.jobinterview.service.PreparationRunPersistenceService;
import java.util.Map;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

@Component
public class PreparationTaskConsumer
    extends AbstractStreamConsumer<PreparationTaskConsumer.Payload> {

  private final PreparationRunPersistenceService persistenceService;
  private final JobInterviewPreparationProcessor processor;

  public PreparationTaskConsumer(
      RedisService redisService,
      PreparationRunPersistenceService persistenceService,
      JobInterviewPreparationProcessor processor
  ) {
    super(redisService);
    this.persistenceService = persistenceService;
    this.processor = processor;
  }

  @Override
  protected String taskDisplayName() {
    return "岗位实战准备";
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.JOB_INTERVIEW_PREPARE_GROUP_NAME;
  }

  @Override
  protected Payload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String runId = data.get(AsyncTaskStreamConstants.FIELD_PREPARATION_RUN_ID);
    String userId = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);
    if (runId == null || userId == null) {
      return null;
    }
    try {
      return new Payload(runId, Long.parseLong(userId));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  protected String payloadIdentifier(Payload payload) {
    return "runId=" + payload.runId() + ", userId=" + payload.userId();
  }

  @Override
  protected boolean shouldSkip(Payload payload) {
    return persistenceService.findInternal(payload.runId())
        .map(run -> !payload.userId().equals(run.getUserId())
            || run.getStatus() == PreparationStatus.READY)
        .orElse(true);
  }

  @Override
  protected void markProcessing(Payload payload) {
    persistenceService.markProcessing(payload.runId(), payload.userId());
  }

  @Override
  protected void processBusiness(Payload payload) {
    processor.process(payload.runId(), payload.userId());
  }

  @Override
  protected void markCompleted(Payload payload) {
    // processor 在会话与题目成功落库后原子标记 READY。
  }

  @Override
  protected void markFailed(Payload payload, String error) {
    persistenceService.markFailed(
        payload.runId(), payload.userId(), "PREPARATION_FAILED", error);
  }

  record Payload(String runId, Long userId) {
  }
}
