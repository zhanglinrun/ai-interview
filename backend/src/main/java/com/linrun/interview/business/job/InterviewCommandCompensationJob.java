package com.linrun.interview.business.job;

import com.linrun.interview.business.mapper.InterviewCommandMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 回收服务异常退出后遗留的答案提交租约，避免永久卡住同一面试会话。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewCommandCompensationJob {

  private static final int STALE_SECONDS = 180;

  private final InterviewCommandMapper commandMapper;

  @Scheduled(
      fixedDelayString = "${app.interview.compensation.command-delay-ms:60000}",
      initialDelayString = "${app.interview.compensation.command-initial-delay-ms:30000}")
  public void recoverStaleCommands() {
    LocalDateTime now = LocalDateTime.now();
    int recovered = commandMapper.failStaleProcessingCommands(
        now.minusSeconds(STALE_SECONDS), now);
    if (recovered > 0) {
      log.warn("回收服务异常退出后遗留的面试指令: count={}", recovered);
    }
  }
}
