package com.linrun.interview.business.listener;

import com.linrun.interview.infra.messaging.AbstractStreamProducer;
import com.linrun.interview.infra.messaging.TaskQueueChannel;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.business.service.ReportPersistenceService;
import com.linrun.interview.business.service.ReportTaskPublisher;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReportTaskProducer
    extends AbstractStreamProducer<ReportTaskProducer.Payload>
    implements ReportTaskPublisher {

  private final ReportPersistenceService persistenceService;

  public ReportTaskProducer(
      TaskQueueChannel taskQueueChannel,
      ReportPersistenceService persistenceService
  ) {
    super(taskQueueChannel);
    this.persistenceService = persistenceService;
  }

  @Override
  public void publish(String reportId, Long userId) {
    sendTask(new Payload(reportId, userId));
  }

  @Override
  protected String taskDisplayName() {
    return "证据化复盘";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.INTERVIEW_REPORT_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(Payload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_REPORT_ID, payload.reportId(),
        AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
  }

  @Override
  protected String payloadIdentifier(Payload payload) {
    return "reportId=" + payload.reportId() + ", userId=" + payload.userId();
  }

  @Override
  protected void onSendFailed(Payload payload, String error) {
    persistenceService.markFailed(
        payload.reportId(), payload.userId(), "QUEUE_UNAVAILABLE", error);
  }

  record Payload(String reportId, Long userId) {
  }
}
