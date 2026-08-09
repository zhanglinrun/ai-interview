package com.linrun.interview.business.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.messaging.AbstractRabbitTaskConsumer;
import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
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
  public void onMessage(Message message) {
    handle(message);
  }
}
