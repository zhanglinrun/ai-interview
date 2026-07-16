package com.linrun.interview.modules.capability.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** fresh schema 启动后幂等导入内置能力目录；测试可通过配置关闭。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.capability-content",
    name = "auto-import",
    havingValue = "true",
    matchIfMissing = true)
public class CapabilityContentBootstrap implements ApplicationRunner {

  private final ContentImportService contentImportService;

  @Override
  public void run(ApplicationArguments args) {
    contentImportService.importClasspathCatalog();
  }
}
