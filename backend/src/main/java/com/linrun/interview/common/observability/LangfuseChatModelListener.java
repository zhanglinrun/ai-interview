package com.linrun.interview.common.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 全局 LLM 调用监听器（P5）：把每次 {@code ChatModel}/{@code StreamingChatModel} 调用
 * 自动记为 Langfuse 的 generation span，挂到当前活跃 span（如 interviewer/critic）之下，
 * 采集 model / prompt / completion / tokenUsage / 延迟。
 *
 * <p>由 {@link com.linrun.interview.common.ai.LlmProviderRegistry} 在构造底层
 * OpenAI 模型时经 {@code .listeners(...)} 注入。仅在 langfuse.enabled=true 时注册为 Bean。
 * 通过 {@code attributes()} 在 onRequest/onResponse 间传递 span 句柄。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.observability.langfuse", name = "enabled", havingValue = "true")
public class LangfuseChatModelListener implements ChatModelListener {

  private static final String ATTR_SPAN = "langfuse.generation.span";

  private final LangfuseTracer tracer;

  public LangfuseChatModelListener(LangfuseTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public void onRequest(ChatModelRequestContext requestContext) {
    if (!tracer.isEnabled()) {
      return;
    }
    ChatRequest request = requestContext.chatRequest();
    String model = modelName(request);
    LangfuseSpan span = tracer.generation(
        model == null ? "llm" : "llm:" + model, model, summarizeInput(request));
    requestContext.attributes().put(ATTR_SPAN, span);
  }

  @Override
  public void onResponse(ChatModelResponseContext responseContext) {
    Object raw = responseContext.attributes().get(ATTR_SPAN);
    if (!(raw instanceof LangfuseSpan span)) {
      return;
    }
    ChatResponse response = responseContext.chatResponse();
    String output = null;
    AiMessage aiMessage = response == null ? null : response.aiMessage();
    if (aiMessage != null) {
      output = aiMessage.text();
    }
    TokenUsage usage = response == null ? null : response.tokenUsage();
    tracer.endGeneration(span, output,
        usage == null ? null : usage.inputTokenCount(),
        usage == null ? null : usage.outputTokenCount(),
        usage == null ? null : usage.totalTokenCount());
  }

  @Override
  public void onError(ChatModelErrorContext errorContext) {
    Object raw = errorContext.attributes().get(ATTR_SPAN);
    if (raw instanceof LangfuseSpan span) {
      tracer.endError(span, errorContext.error());
    }
  }

  private String modelName(ChatRequest request) {
    if (request == null) {
      return null;
    }
    ChatRequestParameters parameters = request.parameters();
    return parameters == null ? null : parameters.modelName();
  }

  private String summarizeInput(ChatRequest request) {
    if (request == null || request.messages() == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    request.messages().forEach(m -> {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(m.type()).append(": ").append(m);
    });
    return sb.toString();
  }
}
