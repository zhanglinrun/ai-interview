package com.linrun.interview.rag.config;

import com.linrun.interview.rag.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 启动时校验本地 Rerank 模型是否就绪。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankStartupValidator implements ApplicationRunner {

  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;
  private final ResourceLoader resourceLoader;

  @Override
  public void run(ApplicationArguments args) {
    if (!queryProperties.getRerank().isEnabled()) {
      return;
    }
    String modelPath = queryProperties.getRerank().getLocal().getModelPath();
    String tokenizerPath = queryProperties.getRerank().getLocal().getTokenizerPath();
    boolean modelExists = resourceExists(modelPath);
    boolean tokenizerExists = resourceExists(tokenizerPath);
    if (modelExists && tokenizerExists && rerankService.isEnabled()) {
      log.info("[RerankStartup] 本地 ONNX BGE rerank 模型就绪");
      return;
    }
    String message = """
        [RerankStartup] 本地 ONNX rerank 模型未就绪（modelExists=%s, tokenizerExists=%s）。
        请按 backend/src/main/resources/model/bge-reranker-model/README.md 下载模型。
        """.formatted(modelExists, tokenizerExists);
    if (queryProperties.getRerank().isFailFastOnMissingModel()) {
      throw new IllegalStateException(message.strip());
    }
    log.warn(message);
  }

  private boolean resourceExists(String location) {
    if (location == null || location.isBlank()) {
      return false;
    }
    Resource resource = resourceLoader.getResource(location);
    return resource.exists();
  }
}
