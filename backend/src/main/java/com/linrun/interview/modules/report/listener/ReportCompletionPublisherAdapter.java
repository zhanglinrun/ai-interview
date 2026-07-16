package com.linrun.interview.modules.report.listener;

import com.linrun.interview.modules.jobinterview.service.JobInterviewCompletionPublisher;
import com.linrun.interview.modules.report.service.ReportApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 岗位实战完成事务提交后，创建客观事实并投递异步复盘任务。 */
@Component
@RequiredArgsConstructor
public class ReportCompletionPublisherAdapter implements JobInterviewCompletionPublisher {

  private final ReportApplicationService reportService;

  @Override
  public void publishCompleted(String sessionId, Long userId) {
    reportService.triggerCompleted(sessionId, userId);
  }
}
