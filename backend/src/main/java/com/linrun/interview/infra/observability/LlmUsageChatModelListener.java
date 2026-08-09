package com.linrun.interview.infra.observability;

import com.linrun.interview.business.constant.LlmUsageStatus;
import com.linrun.interview.business.service.LlmUsageService;
import com.linrun.interview.business.service.LlmUsageService.Capture;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 只采集模型、耗时与 Token；不读取或保存 Prompt、回答、Key 等敏感正文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmUsageChatModelListener implements ChatModelListener {

  private static final String ATTR_INVOCATION = "llm.usage.invocation";

  private final LlmUsageService usageService;

  @Override
  public void onRequest(ChatModelRequestContext requestContext) {
    LlmUsageContext.Context context = LlmUsageContext.current();
    if (context == null || context.userId() == null) {
      return;
    }
    requestContext.attributes().put(ATTR_INVOCATION, new Invocation(
        context.userId(), context.sessionId(), context.reportId(), context.operation(),
        context.provider(), modelName(requestContext.chatRequest()),
        context.nextRetryCount(), context.degradedReason(), TraceContext.getTraceId(),
        context.agentRunId(), context.ragRunId(), context.spanId(),
        System.nanoTime()));
  }

  @Override
  public void onResponse(ChatModelResponseContext responseContext) {
    Invocation invocation = invocation(responseContext.attributes().get(ATTR_INVOCATION));
    if (invocation == null) {
      return;
    }
    ChatResponse response = responseContext.chatResponse();
    TokenUsage tokens = response == null ? null : response.tokenUsage();
    save(invocation, invocation.degradedReason() == null
            ? LlmUsageStatus.SUCCEEDED : LlmUsageStatus.DEGRADED,
        tokens == null ? null : tokens.inputTokenCount(),
        tokens == null ? null : tokens.outputTokenCount(),
        tokens == null ? null : tokens.totalTokenCount(),
        invocation.degradedReason());
  }

  @Override
  public void onError(ChatModelErrorContext errorContext) {
    Invocation invocation = invocation(errorContext.attributes().get(ATTR_INVOCATION));
    if (invocation == null) {
      return;
    }
    String reason = errorContext.error() == null
        ? "LLM 调用失败" : errorContext.error().getClass().getSimpleName();
    save(invocation, LlmUsageStatus.FAILED, null, null, null, reason);
  }

  private void save(
      Invocation invocation,
      LlmUsageStatus status,
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens,
      String degradedReason
  ) {
    try {
      usageService.record(new Capture(
          invocation.userId(), invocation.sessionId(), invocation.reportId(),
          invocation.operation(), invocation.provider(), invocation.model(), status,
          Math.max(0L, (System.nanoTime() - invocation.startedNanos()) / 1_000_000L),
          inputTokens, outputTokens, totalTokens, invocation.retryCount(),
          degradedReason, invocation.traceId(), invocation.agentRunId(),
          invocation.ragRunId(), invocation.spanId()));
    } catch (Exception e) {
      // 用量记录不得反向破坏业务调用；日志也不输出请求或响应正文。
      log.warn("记录 LLM 用量失败: userId={}, operation={}",
          invocation.userId(), invocation.operation(), e);
    }
  }

  private Invocation invocation(Object value) {
    return value instanceof Invocation invocation ? invocation : null;
  }

  private String modelName(ChatRequest request) {
    if (request == null) {
      return null;
    }
    ChatRequestParameters parameters = request.parameters();
    return parameters == null ? null : parameters.modelName();
  }

  private record Invocation(
      Long userId,
      String sessionId,
      String reportId,
      String operation,
      String provider,
      String model,
      int retryCount,
      String degradedReason,
      String traceId,
      String agentRunId,
      String ragRunId,
      String spanId,
      long startedNanos
  ) {
  }
}
