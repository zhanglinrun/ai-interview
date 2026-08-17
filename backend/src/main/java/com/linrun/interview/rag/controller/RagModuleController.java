package com.linrun.interview.rag.controller;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.model.QueryRequest;
import com.linrun.interview.rag.model.RagSourceDTO;
import com.linrun.interview.rag.service.IntentRecognitionService;
import com.linrun.interview.rag.service.InterviewQueryTransformer;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;
import com.linrun.interview.rag.constant.InterviewIntent;
import com.linrun.interview.ai.service.RagPromptService;
import com.linrun.interview.rag.service.RerankService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linrun.interview.ai.service.PromptTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 模块调试端点。
 */
@RestController
@RequestMapping("/api/v1/rag/module")
@RequiredArgsConstructor
public class RagModuleController {

  private final IntentRecognitionService intentRecognitionService;
  private final RagPromptService ragPromptService;
  private final RerankService rerankService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final KnowledgeBaseQueryService knowledgeBaseQueryService;

  @GetMapping("/intent")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 20)
  public Result<IntentRecognitionResult> testIntent(@RequestParam String question) {
    return Result.success(intentRecognitionService.recognize(question));
  }

  @GetMapping("/prompt")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 20)
  public Result<String> testPrompt(@RequestParam String question) {
    IntentRecognitionResult intent = intentRecognitionService.recognize(question);
    InterviewIntent resolved = intent != null ? intent.resolvedIntent() : InterviewIntent.TECH_KB;
    return Result.success(ragPromptService.getPrompt(resolved));
  }

  @GetMapping("/rewrite")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<List<String>> testRewrite(@RequestParam String question) {
    ChatModel chatModel = llmProviderRegistry.getUserChatModel(UserContext.requireUserId());
    String templateText = loadClasspathPrompt("classpath:prompts/rag/knowledgebase-query-rewrite.txt");
    PromptTemplate template = new PromptTemplate(templateText);
    InterviewQueryTransformer transformer = new InterviewQueryTransformer(chatModel, template, true);
    return Result.success(transformer.transform(new Query(question)).stream()
        .map(Query::text)
        .toList());
  }

  @GetMapping("/rerank")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<String> testRerank(@RequestParam(defaultValue = "什么是 Java 虚拟机？") String question) {
    if (!rerankService.isEnabled()) {
      return Result.error("Rerank 未启用或本地模型不可用");
    }
    List<TextSegment> docs = List.of(
        TextSegment.from("Java 是面向对象语言，JVM 提供跨平台能力。"),
        TextSegment.from("Python 常用于数据科学与 AI。"),
        TextSegment.from("Spring 是最流行的 Java 框架之一。"));
    var scores = rerankService.scoreAll(docs, question).content();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < docs.size(); i++) {
      sb.append(String.format(Locale.ROOT, "[score=%.4f] %s%n", scores.get(i), docs.get(i).text()));
    }
    return Result.success(sb.toString().trim());
  }

  /**
   * 端到端检索调试：走评测 augment（ES-only，关改写/HyDE/分解，保留混合检索/RRF/BGE/父扩展）。
   */
  @GetMapping("/retrieve")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Map<String, Object>> testRetrieve(
      @RequestParam String question,
      @RequestParam List<Long> knowledgeBaseIds) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
      return Result.error("knowledgeBaseIds 不能为空");
    }
    List<TextSegment> segments = knowledgeBaseQueryService.retrieveForEvaluation(
        knowledgeBaseIds, question);
    List<String> snippets = segments.stream().map(TextSegment::text).toList();
    return Result.success(Map.of(
        "count", snippets.size(),
        "snippets", snippets));
  }

  /**
   * 同步问答调试：返回 answer + sources，便于对照引用与检索片段。
   */
  @GetMapping("/query")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<Map<String, Object>> testQuery(
      @RequestParam String question,
      @RequestParam List<Long> knowledgeBaseIds) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
      return Result.error("knowledgeBaseIds 不能为空");
    }
    var response = knowledgeBaseQueryService.queryKnowledgeBase(
        new QueryRequest(knowledgeBaseIds, question));
    List<String> sourceSnippets = response.sources() == null ? List.of()
        : response.sources().stream().map(RagSourceDTO::snippet).toList();
    return Result.success(Map.of(
        "answer", response.answer(),
        "sourceCount", sourceSnippets.size(),
        "sources", sourceSnippets));
  }

  private String loadClasspathPrompt(String location) {
    try {
      var resource = new org.springframework.core.io.DefaultResourceLoader()
          .getResource(location);
      return resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("无法加载 prompt: " + location, e);
    }
  }
}
