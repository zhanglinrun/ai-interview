package com.linrun.interview.business.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.algorithm-content",
    name = "auto-import",
    havingValue = "true",
    matchIfMissing = true)
public class AlgorithmContentBootstrap implements ApplicationRunner {

  private final AlgorithmContentImportService importService;

  @Override
  public void run(ApplicationArguments args) {
    importService.importClasspathCatalog();
  }
}
