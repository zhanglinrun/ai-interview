package com.linrun.interview.modules.jobinterview.listener;

import com.linrun.interview.modules.jobinterview.service.JobInterviewLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobInterviewLifecycleJob {

  private final JobInterviewLifecycleService lifecycleService;

  @Scheduled(fixedDelayString = "${app.job-interview.lifecycle-delay-ms:60000}")
  public void reconcile() {
    try {
      int changed = lifecycleService.reconcileCandidates();
      if (changed > 0) {
        log.info("岗位实战生命周期补偿完成: changed={}", changed);
      }
    } catch (Exception e) {
      log.error("岗位实战生命周期补偿失败", e);
    }
  }
}
