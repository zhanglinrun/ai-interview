package com.linrun.interview.modules.knowledgebase.config;

import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import com.linrun.interview.modules.knowledgebase.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 启动时校验本地 Rerank 模型是否就绪（对齐业界实践 BgeScoringModel 预加载检查）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankStartupValidator implements ApplicationRunner {

  private static final String PROVIDER_LOCAL = "local";

  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;
  private final ResourceLoader resourceLoader;

  @Override
  public void run(ApplicationArguments args) {
    if (!queryProperties.getRerank().isEnabled()) {
      return;
    }
    if (!PROVIDER_LOCAL.equalsIgnoreCase(queryProperties.getRerank().getProvider())) {
      log.info("[RerankStartup] 配置 provider={}，跳过本地模型文件检查",
          queryProperties.getRerank().getProvider());
      return;
    }
    String modelPath = queryProperties.getRerank().getLocal().getModelPath();
    String tokenizerPath = queryProperties.getRerank().getLocal().getTokenizerPath();
    boolean modelExists = resourceExists(modelPath);
    boolean tokenizerExists = resourceExists(tokenizerPath);
    if (modelExists && tokenizerExists && rerankService.isEnabled()) {
      log.info("[RerankStartup] 本地 ONNX rerank 模型就绪: provider={}",
          rerankService.getEffectiveProvider());
      return;
    }
    log.warn("""
        [RerankStartup] 本地 ONNX rerank 模型未就绪（modelExists={}, tokenizerExists={}, effectiveProvider={}）。
        请按 backend/src/main/resources/model/bge-reranker-model/README.md 下载模型，
        或设置 APP_AI_RAG_RERANK_PROVIDER=cloud 使用云端 gte-rerank。
        """, modelExists, tokenizerExists, rerankService.getEffectiveProvider());
  }

  private boolean resourceExists(String location) {
    if (location == null || location.isBlank()) {
      return false;
    }
    Resource resource = resourceLoader.getResource(location);
    return resource.exists();
  }
}
