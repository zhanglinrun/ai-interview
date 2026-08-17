package com.linrun.interview.rag.service;

import com.linrun.interview.ai.service.PromptTemplate;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 组合查询改写器：先改写，再可选 HyDE 追加假设文档 query（多路召回）。
 */
@Slf4j
public class InterviewCompositeQueryTransformer implements QueryTransformer {

  private final InterviewQueryTransformer rewriteTransformer;
  private final ChatModel hydeChatModel;
  private final PromptTemplate hydePromptTemplate;
  private final boolean hydeEnabled;
  private final int hydeMaxChars;
  private final long hydeTimeoutMs;

  public InterviewCompositeQueryTransformer(InterviewQueryTransformer rewriteTransformer,
                                            ChatModel hydeChatModel,
                                            PromptTemplate hydePromptTemplate,
                                            boolean hydeEnabled,
                                            int hydeMaxChars) {
    this(rewriteTransformer, hydeChatModel, hydePromptTemplate, hydeEnabled, hydeMaxChars, 4000);
  }

  public InterviewCompositeQueryTransformer(InterviewQueryTransformer rewriteTransformer,
                                            ChatModel hydeChatModel,
                                            PromptTemplate hydePromptTemplate,
                                            boolean hydeEnabled,
                                            int hydeMaxChars,
                                            long hydeTimeoutMs) {
    this.rewriteTransformer = rewriteTransformer;
    this.hydeChatModel = hydeChatModel;
    this.hydePromptTemplate = hydePromptTemplate;
    this.hydeEnabled = hydeEnabled;
    this.hydeMaxChars = hydeMaxChars;
    this.hydeTimeoutMs = hydeTimeoutMs;
  }

  @Override
  public List<Query> transform(Query query) {
    List<Query> rewritten = rewriteTransformer.transform(query);
    Query primary = rewritten.isEmpty() ? query : rewritten.get(0);
    if (!hydeEnabled || hydeChatModel == null || hydePromptTemplate == null) {
      return rewritten;
    }
    try {
      String hydeDoc = generateHyde(primary.text());
      if (hydeDoc == null || hydeDoc.isBlank()) {
        return rewritten;
      }
      Query hydeQuery = primary.metadata() == null
          ? Query.from(hydeDoc)
          : Query.from(hydeDoc, primary.metadata());
      List<Query> queries = new ArrayList<>(2);
      queries.add(primary);
      queries.add(hydeQuery);
      log.info("[InterviewCompositeQueryTransformer] HyDE 追加假设文档 query, chars={}", hydeDoc.length());
      return queries;
    } catch (Exception e) {
      log.warn("[InterviewCompositeQueryTransformer] HyDE 生成失败，仅使用改写 query: {}", e.getMessage(), e);
      return rewritten;
    }
  }

  private String generateHyde(String question) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("question", question);
    variables.put("maxChars", hydeMaxChars);
    String prompt = hydePromptTemplate.render(variables);
    String text = hydeTimeoutMs > 0
        ? invokeHydeWithTimeout(prompt)
        : invokeHyde(prompt);
    if (text == null) {
      return null;
    }
    String normalized = text.trim();
    if (normalized.length() > hydeMaxChars) {
      normalized = normalized.substring(0, hydeMaxChars);
    }
    return normalized;
  }

  private String invokeHydeWithTimeout(String prompt) {
    try {
      return CompletableFuture.supplyAsync(() -> invokeHyde(prompt))
          .orTimeout(hydeTimeoutMs, TimeUnit.MILLISECONDS)
          .join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TimeoutException) {
        throw new IllegalStateException("HyDE 超过 " + hydeTimeoutMs + "ms", cause);
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }

  private String invokeHyde(String prompt) {
    return hydeChatModel.chat(ChatRequest.builder()
            .messages(UserMessage.from(prompt))
            .build())
        .aiMessage().text();
  }
}
