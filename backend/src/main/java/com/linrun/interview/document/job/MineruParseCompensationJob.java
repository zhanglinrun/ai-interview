package com.linrun.interview.document.job;

import com.linrun.interview.document.config.MineruProperties;
import com.linrun.interview.document.entity.DocumentParseTaskEntity;
import com.linrun.interview.document.service.impl.DocumentParseTaskService;
import com.linrun.interview.document.service.impl.MineruProcessServiceImpl;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 恢复进程退出时遗留的 MinerU provider task；不读取 UserContext。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "file.parse.mineru",
    name = {"enabled", "compensation-enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class MineruParseCompensationJob {

  private static final int BATCH_SIZE = 20;

  private final MineruProperties properties;
  private final DocumentParseTaskService taskService;
  private final MineruProcessServiceImpl mineruProcessService;

  @Scheduled(fixedDelayString = "${file.parse.mineru.compensation-delay-ms:30000}")
  @XxlJob("ragMineruParseCompensation")
  public void recoverStaleTasks() {
    LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(
        Math.max(properties.getCompensationStaleSeconds(), 1));
    List<DocumentParseTaskEntity> tasks = taskService.listRecoverable(staleBefore, BATCH_SIZE);
    if (tasks.isEmpty()) {
      return;
    }
    int recovered = 0;
    for (DocumentParseTaskEntity task : tasks) {
      if (mineruProcessService.recoverStaleTask(task)) {
        recovered++;
      }
    }
    log.info("MinerU 解析补偿完成: scanned={}, recovered={}", tasks.size(), recovered);
  }
}
