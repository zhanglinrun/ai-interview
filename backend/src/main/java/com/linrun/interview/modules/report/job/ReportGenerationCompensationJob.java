package com.linrun.interview.modules.report.job;

import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.modules.report.model.InterviewReportEntity;
import com.linrun.interview.modules.report.service.ReportPersistenceService;
import com.linrun.interview.modules.report.service.ReportTaskPublisher;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报告生成租约补偿：覆盖 claim 成功后 JVM 宕机、消息丢失和投递后消费者未及时接管。
 *
 * <p>任务只扫描过期租约或超过安静期仍未 claim 的 GENERATING 报告；每次重投前使用数据库
 * CAS 释放/预留，避免多实例重复补偿。即使进程在 CAS 后再次宕机，updated_at 安静期过后
 * 仍会重新进入扫描，因此不会永久停在 GENERATING。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportGenerationCompensationJob {

  private final ReportPersistenceService persistenceService;
  private final ReportTaskPublisher taskPublisher;

  @Scheduled(
      fixedDelayString = "${app.report.generation.compensation-delay-ms:60000}",
      initialDelayString = "${app.report.generation.compensation-initial-delay-ms:30000}"
  )
  @DistributeLock(
      key = "'report:generation:compensation'",
      waitTime = 0,
      leaseTime = 60,
      message = "报告生成补偿任务已在其他实例执行"
  )
  public void runCompensation() {
    List<InterviewReportEntity> recoverable = persistenceService.findRecoverableGenerations();
    if (recoverable.isEmpty()) {
      return;
    }
    int dispatched = 0;
    for (InterviewReportEntity report : recoverable) {
      try {
        if (!persistenceService.prepareRecoveryDispatch(
            report.getReportId(), report.getUserId())) {
          continue;
        }
        taskPublisher.publish(report.getReportId(), report.getUserId());
        dispatched++;
      } catch (RuntimeException e) {
        log.error(
            "报告生成补偿重投失败: reportId={}, userId={}",
            report.getReportId(), report.getUserId(), e);
      }
    }
    log.info("报告生成补偿完成: 重投 {}/{}", dispatched, recoverable.size());
  }
}
