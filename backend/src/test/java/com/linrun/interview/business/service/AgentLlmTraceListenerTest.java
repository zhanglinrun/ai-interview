package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.observability.LlmUsageContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent chat span 监听")
class AgentLlmTraceListenerTest {

  @Mock
  private AgentTraceService agentTraceService;
  @Mock
  private ChatModelRequestContext requestContext;
  @Mock
  private ChatModelResponseContext responseContext;

  @AfterEach
  void clearContext() {
    LlmUsageContext.replace(null);
  }

  @Test
  @DisplayName("没有 agentRunId 时不写 agent_steps（避免污染 RAG）")
  void skipsWhenNoAgentRun() {
    AgentLlmTraceListener listener = newListener(true);

    try (var ignored = LlmUsageContext.open(1L, "kb-session", null, "rag.query")) {
      listener.onRequest(requestContext);
    }

    verify(agentTraceService, never()).appendQuietly(any(), any());
  }

  @Test
  @DisplayName("Agent 上下文存在时写入截断后的 chat span，并恢复外层 span")
  void writesChatSpanWhenAgentContextPresent() {
    AgentLlmTraceListener listener = newListener(true);
    Map<Object, Object> attributes = new HashMap<>();
    ChatRequest request = ChatRequest.builder()
        .messages(UserMessage.from("下一题请围绕 Redis"))
        .build();
    when(requestContext.chatRequest()).thenReturn(request);
    when(requestContext.attributes()).thenReturn(attributes);
    when(responseContext.attributes()).thenReturn(attributes);
    when(responseContext.chatResponse()).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from("请说明 Redis 过期策略"))
        .tokenUsage(new TokenUsage(11, 7))
        .build());

    try (var ignored = LlmUsageContext.open(
        7L, "sess-1", null, "agent.question", "BYOK", null, "run-1", null, "root-1")) {
      try (var role = LlmUsageContext.overlayAgentRole("interviewer")) {
        listener.onRequest(requestContext);
        assertThat(LlmUsageContext.current().spanId()).startsWith("span-chat-");
        listener.onResponse(responseContext);
        assertThat(LlmUsageContext.current().spanId()).isEqualTo("root-1");
      }
    }

    ArgumentCaptor<AgentSpanRecord> span = ArgumentCaptor.forClass(AgentSpanRecord.class);
    verify(agentTraceService).appendQuietly(any(AgentRunHandle.class), span.capture());
    assertThat(span.getValue().action()).isEqualTo("chat");
    assertThat(span.getValue().role()).isEqualTo("interviewer");
    assertThat(span.getValue().parentSpanId()).isEqualTo("root-1");
    assertThat(span.getValue().actionInput()).contains("下一题请围绕 Redis");
    assertThat(span.getValue().observation()).contains("Redis 过期策略");
    assertThat(span.getValue().metadataJson()).contains("\"kind\":\"chat\"");
    assertThat(span.getValue().metadataJson()).contains("\"inputTokens\":11");
  }

  @Test
  @DisplayName("关闭 capture-content 时只记条数，不落正文")
  void redactsContentWhenDisabled() {
    AgentLlmTraceListener listener = newListener(false);
    Map<Object, Object> attributes = new HashMap<>();
    when(requestContext.chatRequest()).thenReturn(ChatRequest.builder()
        .messages(UserMessage.from("secret prompt"))
        .build());
    when(requestContext.attributes()).thenReturn(attributes);
    when(responseContext.attributes()).thenReturn(attributes);
    when(responseContext.chatResponse()).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from("secret answer"))
        .build());

    try (var ignored = LlmUsageContext.open(
        7L, "sess-1", null, "agent.question", "BYOK", null, "run-1", null, "root-1")) {
      listener.onRequest(requestContext);
      listener.onResponse(responseContext);
    }

    ArgumentCaptor<AgentSpanRecord> span = ArgumentCaptor.forClass(AgentSpanRecord.class);
    verify(agentTraceService).appendQuietly(any(AgentRunHandle.class), span.capture());
    assertThat(span.getValue().actionInput()).startsWith("messages=");
    assertThat(span.getValue().actionInput()).doesNotContain("secret prompt");
    assertThat(span.getValue().observation()).startsWith("chars=");
    assertThat(span.getValue().observation()).doesNotContain("secret answer");
  }

  private AgentLlmTraceListener newListener(boolean captureContent) {
    AgentOrchestrationProperties properties = new AgentOrchestrationProperties();
    properties.getTrace().setCaptureContent(captureContent);
    return new AgentLlmTraceListener(agentTraceService, properties, new ObjectMapper());
  }
}
