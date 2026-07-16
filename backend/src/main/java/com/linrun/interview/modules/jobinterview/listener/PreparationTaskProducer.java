package com.linrun.interview.modules.jobinterview.listener;

import com.linrun.interview.common.async.AbstractStreamProducer;
import com.linrun.interview.common.async.TaskQueueChannel;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.modules.jobinterview.service.PreparationRunPersistenceService;
import com.linrun.interview.modules.jobinterview.service.PreparationTaskPublisher;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PreparationTaskProducer
    extends AbstractStreamProducer<PreparationTaskProducer.Payload>
    implements PreparationTaskPublisher {

  private final PreparationRunPersistenceService persistenceService;

  public PreparationTaskProducer(
      TaskQueueChannel taskQueueChannel,
      PreparationRunPersistenceService persistenceService
  ) {
    super(taskQueueChannel);
    this.persistenceService = persistenceService;
  }

  @Override
  public void publish(String runId, Long userId) {
    sendTask(new Payload(runId, userId));
  }

  @Override
  protected String taskDisplayName() {
    return "岗位实战准备";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.JOB_INTERVIEW_PREPARE_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(Payload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_PREPARATION_RUN_ID, payload.runId(),
        AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
  }

  @Override
  protected String payloadIdentifier(Payload payload) {
    return "runId=" + payload.runId() + ", userId=" + payload.userId();
  }

  @Override
  protected void onSendFailed(Payload payload, String error) {
    persistenceService.markFailed(payload.runId(), payload.userId(), "QUEUE_UNAVAILABLE", error);
  }

  record Payload(String runId, Long userId) {
  }
}
