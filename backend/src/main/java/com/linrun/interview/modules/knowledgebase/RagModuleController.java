package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryRouter;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryTransformer;
import com.linrun.interview.modules.knowledgebase.rag.InterviewIntent;
import com.linrun.interview.modules.knowledgebase.service.RagPromptService;
import com.linrun.interview.modules.knowledgebase.service.RerankService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linrun.interview.common.ai.PromptTemplate;

import java.util.List;
import java.util.Locale;

/**
 * RAG 模块调试端点（对齐业界实践 RagModuleController，面试域简化版）。
 */
@RestController
@RequestMapping("/api/rag/module")
@RequiredArgsConstructor
public class RagModuleController {

  private final IntentRecognitionService intentRecognitionService;
  private final RagPromptService ragPromptService;
  private final RerankService rerankService;
  private final LlmProviderRegistry llmProviderRegistry;

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
    String templateText = loadClasspathPrompt("classpath:prompts/knowledgebase-query-rewrite.st");
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
      return Result.error("Rerank 未启用或模型不可用");
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

  @GetMapping("/router-strategy")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<String> testRouterStrategy(@RequestParam String question) {
    return Result.success(InterviewQueryRouter.class.getSimpleName()
        + " 已接入 KnowledgeBaseQueryService 编排（本端点仅作连通性探测）");
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
