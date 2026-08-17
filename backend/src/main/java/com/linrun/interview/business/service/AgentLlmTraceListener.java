package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.observability.LlmUsageContext;
import com.linrun.interview.infra.observability.LlmUsageContext.Context;
import com.linrun.interview.infra.observability.TraceContext;
import com.linrun.interview.business.vo.AgentTraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 只在已打开 AgentRun 的调用上写 chat span。RAG / 普通问答不会进 agent_steps。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLlmTraceListener implements ChatModelListener {

  private static final String ATTR_PENDING = "agent.trace.pending";

  private final AgentTraceService agentTraceService;
  private final AgentOrchestrationProperties properties;
  private final ObjectMapper objectMapper;

  @Override
  public void onRequest(ChatModelRequestContext requestContext) {
    Context usage = LlmUsageContext.current();
    if (!canTrace(usage)) {
      return;
    }
    String chatSpanId = "span-chat-" + UUID.randomUUID();
    Pending pending = new Pending(
        chatSpanId,
        usage.spanId(),
        usage,
        System.nanoTime(),
        summarizeInput(requestContext.chatRequest()),
        modelName(requestContext.chatRequest()));
    requestContext.attributes().put(ATTR_PENDING, pending);
    LlmUsageContext.replace(usage.withSpanId(chatSpanId));
  }

  @Override
  public void onResponse(ChatModelResponseContext responseContext) {
    Pending pending = pendingOf(responseContext.attributes().get(ATTR_PENDING));
    if (pending == null) {
      return;
    }
    ChatResponse response = responseContext.chatResponse();
    TokenUsage tokens = response == null ? null : response.tokenUsage();
    write(pending, "COMPLETED",
        summarizeOutput(response),
        tokens == null ? null : tokens.inputTokenCount(),
        tokens == null ? null : tokens.outputTokenCount(),
        null);
    restoreSpan(pending);
  }

  @Override
  public void onError(ChatModelErrorContext errorContext) {
    Pending pending = pendingOf(errorContext.attributes().get(ATTR_PENDING));
    if (pending == null) {
      return;
    }
    String reason = errorContext.error() == null
        ? "LLM 调用失败" : errorContext.error().getClass().getSimpleName();
    write(pending, "FAILED", reason, null, null, reason);
    restoreSpan(pending);
  }

  private void write(Pending pending, String status, String output,
                     Integer inputTokens, Integer outputTokens, String degraded) {
    Context usage = pending.usage();
    AgentRunHandle run = new AgentRunHandle(
        usage.agentRunId(),
        TraceContext.getTraceId() == null ? usage.spanId() : TraceContext.getTraceId(),
        null,
        usage.sessionId(),
        usage.userId(),
        usage.operation(),
        usage.spanId(),
        LocalDateTime.now());
    long latencyMs = Math.max(0L, (System.nanoTime() - pending.startedNanos()) / 1_000_000L);
    agentTraceService.appendQuietly(run, new AgentSpanRecord(
        pending.chatSpanId(),
        pending.parentSpanId(),
        resolveRole(usage),
        "chat",
        pending.input(),
        output,
        status,
        latencyMs,
        AgentSpanMetadata.write(objectMapper, AgentSpanMetadata.KIND_CHAT,
            pending.model(), inputTokens, outputTokens, usage.operation()),
        0,
        usage.questionIndex()));
    if (degraded != null) {
      log.debug("chat span 失败: runId={}, reason={}", usage.agentRunId(), degraded);
    }
  }

  private void restoreSpan(Pending pending) {
    LlmUsageContext.replace(pending.usage());
  }

  private boolean canTrace(Context usage) {
    return usage != null
        && usage.agentRunId() != null && !usage.agentRunId().isBlank()
        && usage.userId() != null
        && usage.sessionId() != null && !usage.sessionId().isBlank();
  }

  private String resolveRole(Context usage) {
    if (usage.agentRole() != null && !usage.agentRole().isBlank()) {
      return usage.agentRole();
    }
    String operation = usage.operation() == null ? "" : usage.operation();
    if (operation.contains("planning")) {
      return AgentTraceStep.ROLE_PLANNER;
    }
    if (operation.contains("question")) {
      return AgentTraceStep.ROLE_INTERVIEWER;
    }
    if (operation.contains("evaluat")) {
      return AgentTraceStep.ROLE_EVALUATOR;
    }
    return AgentTraceStep.ROLE_ORCHESTRATOR;
  }

  private String summarizeInput(ChatRequest request) {
    List<ChatMessage> messages = request == null ? List.of() : request.messages();
    int chars = 0;
    for (ChatMessage message : messages) {
      chars += textOf(message).length();
    }
    if (!captureContent()) {
      return "messages=" + messages.size() + " chars=" + chars;
    }
    StringBuilder builder = new StringBuilder();
    for (ChatMessage message : messages) {
      if (builder.length() > 0) {
        builder.append('\n');
      }
      builder.append(roleOf(message)).append(": ").append(textOf(message));
    }
    return truncate(builder.toString());
  }

  private String summarizeOutput(ChatResponse response) {
    if (response == null || response.aiMessage() == null) {
      return captureContent() ? "" : "empty completion";
    }
    AiMessage message = response.aiMessage();
    String text = message.text() == null ? "" : message.text();
    List<ToolExecutionRequest> toolRequests = toolRequests(message);
    if (!toolRequests.isEmpty()) {
      String tools = toolRequests.stream()
          .map(item -> item.name() == null ? "tool" : item.name())
          .reduce((left, right) -> left + "," + right)
          .orElse("");
      text = text.isBlank() ? "tool_calls=" + tools : text + "\ntool_calls=" + tools;
    }
    if (!captureContent()) {
      return "chars=" + text.length()
          + (toolRequests.isEmpty() ? "" : " tool_calls=" + toolRequests.size());
    }
    return truncate(text);
  }

  private boolean captureContent() {
    return properties.getTrace() != null && properties.getTrace().isCaptureContent();
  }

  private int maxChars() {
    if (properties.getTrace() == null || properties.getTrace().getMaxContentChars() <= 0) {
      return 2000;
    }
    return properties.getTrace().getMaxContentChars();
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    int max = maxChars();
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }

  private String roleOf(ChatMessage message) {
    if (message instanceof SystemMessage) {
      return "system";
    }
    if (message instanceof UserMessage) {
      return "user";
    }
    if (message instanceof AiMessage) {
      return "assistant";
    }
    if (message instanceof ToolExecutionResultMessage) {
      return "tool";
    }
    return "message";
  }

  private String textOf(ChatMessage message) {
    try {
      if (message instanceof UserMessage user) {
        return user.singleText();
      }
      if (message instanceof AiMessage ai) {
        return ai.text() == null ? "" : ai.text();
      }
      if (message instanceof SystemMessage system) {
        return system.text() == null ? "" : system.text();
      }
      if (message instanceof ToolExecutionResultMessage tool) {
        return tool.text() == null ? "" : tool.text();
      }
    } catch (Exception ignored) {
      return "";
    }
    return "";
  }

  private List<ToolExecutionRequest> toolRequests(AiMessage message) {
    if (message == null || message.toolExecutionRequests() == null) {
      return List.of();
    }
    return message.toolExecutionRequests();
  }

  private String modelName(ChatRequest request) {
    if (request == null || request.parameters() == null) {
      return null;
    }
    ChatRequestParameters parameters = request.parameters();
    return parameters.modelName();
  }

  private Pending pendingOf(Object value) {
    return value instanceof Pending pending ? pending : null;
  }

  private record Pending(
      String chatSpanId,
      String parentSpanId,
      Context usage,
      long startedNanos,
      String input,
      String model
  ) {
  }
}
