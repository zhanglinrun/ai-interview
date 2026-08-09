package com.linrun.interview.business.job;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.constant.ReportStatus;
import com.linrun.interview.business.service.ReportPersistenceService;
import com.linrun.interview.business.service.ReportTaskPublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("报告生成租约补偿")
class ReportGenerationCompensationJobTest {

  @Mock
  private ReportPersistenceService persistenceService;
  @Mock
  private ReportTaskPublisher taskPublisher;

  private ReportGenerationCompensationJob job;

  @BeforeEach
  void setUp() {
    job = new ReportGenerationCompensationJob(persistenceService, taskPublisher);
  }

  @Test
  @DisplayName("只有成功取得恢复投递 CAS 的报告才重新发布")
  void shouldPublishOnlyReservedRecovery() {
    InterviewReportEntity reserved = report("report-1", 7L);
    InterviewReportEntity raced = report("report-2", 8L);
    when(persistenceService.findRecoverableGenerations())
        .thenReturn(List.of(reserved, raced));
    when(persistenceService.prepareRecoveryDispatch("report-1", 7L)).thenReturn(true);
    when(persistenceService.prepareRecoveryDispatch("report-2", 8L)).thenReturn(false);

    job.runCompensation();

    verify(taskPublisher).publish("report-1", 7L);
    verify(taskPublisher, never()).publish("report-2", 8L);
  }

  @Test
  @DisplayName("没有过期或丢失任务时不产生空投递")
  void shouldDoNothingWhenNoRecoverableReportExists() {
    when(persistenceService.findRecoverableGenerations()).thenReturn(List.of());

    job.runCompensation();

    verify(taskPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  private InterviewReportEntity report(String reportId, Long userId) {
    return InterviewReportEntity.builder()
        .reportId(reportId)
        .userId(userId)
        .status(ReportStatus.GENERATING)
        .build();
  }
}
