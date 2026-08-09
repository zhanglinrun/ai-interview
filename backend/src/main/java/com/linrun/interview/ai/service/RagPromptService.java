package com.linrun.interview.ai.service;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;


import com.linrun.interview.rag.constant.InterviewIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按意图加载 RAG 系统 Prompt（对齐业界实践 {@code PromptService}）。
 */
@Slf4j
@Service
public class RagPromptService {

  private final Map<InterviewIntent, String> promptCache = new ConcurrentHashMap<>();
  private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
  private final String defaultPromptPath;

  public RagPromptService(KnowledgeBaseQueryProperties queryProperties) {
    this.defaultPromptPath = queryProperties.getSystemPromptPath();
  }

  public String getPrompt(InterviewIntent intent) {
    if (intent == null || intent == InterviewIntent.OFF_TOPIC || intent.getPromptFile() == null) {
      return loadDefaultPrompt();
    }
    return promptCache.computeIfAbsent(intent, this::loadPromptFromFile);
  }

  private String loadPromptFromFile(InterviewIntent intent) {
    try {
      Resource resource = resolver.getResource("classpath:/prompts/" + intent.getPromptFile());
      return FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      log.warn("意图 Prompt 加载失败，回退默认: intent={}, error={}", intent, e.getMessage());
      return loadDefaultPrompt();
    }
  }

  private String loadDefaultPrompt() {
    return promptCache.computeIfAbsent(InterviewIntent.TECH_KB, key -> {
      try {
        Resource resource = resolver.getResource(defaultPromptPath);
        return FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new IllegalStateException("默认 RAG Prompt 加载失败: " + defaultPromptPath, e);
      }
    });
  }
}
