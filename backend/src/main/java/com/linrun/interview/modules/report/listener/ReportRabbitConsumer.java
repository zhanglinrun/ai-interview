package com.linrun.interview.modules.report.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRabbitTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReportRabbitConsumer extends AbstractRabbitTaskConsumer {

  private final ReportTaskConsumer delegate;

  public ReportRabbitConsumer(ObjectMapper objectMapper, ReportTaskConsumer delegate) {
    super(objectMapper);
    this.delegate = delegate;
  }

  @Override
  protected AbstractStreamConsumer<?> delegate() {
    return delegate;
  }

  @RabbitListener(
      queues = AsyncTaskStreamConstants.RABBIT_INTERVIEW_REPORT_QUEUE,
      containerFactory = "taskRabbitListenerContainerFactory")
  public void onMessage(String body) {
    handle(body);
  }
}
