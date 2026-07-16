package com.linrun.interview.modules.report.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.report.model.InterviewReportEntity;
import com.linrun.interview.modules.report.model.ReportStatus;
import com.linrun.interview.modules.report.service.ReportGenerationProcessor;
import com.linrun.interview.modules.report.service.ReportGenerationProcessor.ProcessOutcome;
import com.linrun.interview.modules.report.service.ReportPersistenceService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("报告消息租约消费")
class ReportTaskConsumerTest {

  @Mock
  private RedisService redisService;
  @Mock
  private ReportPersistenceService persistenceService;
  @Mock
  private ReportGenerationProcessor processor;

  private ReportTaskConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new ReportTaskConsumer(redisService, persistenceService, processor);
  }

  @Test
  @DisplayName("有效租约占用时 ACK 当前消息但不写幂等 DONE")
  void shouldNotMarkDoneWhenGenerationIsDeferred() {
    when(redisService.getIdempotencyState(anyString())).thenReturn(null);
    when(persistenceService.findInternal("report-1"))
        .thenReturn(Optional.of(report()));
    when(processor.process("report-1", 7L)).thenReturn(ProcessOutcome.DEFERRED);

    consumer.consumeFromBroker(message("task-1"), 0);

    verify(redisService, never()).markIdempotencyDone(anyString(), any());
  }

  private Map<String, String> message(String taskId) {
    Map<String, String> data = new HashMap<>();
    data.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
    data.put(AsyncTaskStreamConstants.FIELD_REPORT_ID, "report-1");
    data.put(AsyncTaskStreamConstants.FIELD_USER_ID, "7");
    return data;
  }

  private InterviewReportEntity report() {
    return InterviewReportEntity.builder()
        .reportId("report-1")
        .userId(7L)
        .status(ReportStatus.GENERATING)
        .build();
  }
}
