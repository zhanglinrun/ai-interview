package com.linrun.interview.ai.service;

import com.linrun.interview.infra.observability.LlmUsageContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("用户 BYOK 模型用量兜底")
class UserScopedUsageModelTest {

  @AfterEach
  void tearDown() {
    assertThat(LlmUsageContext.current()).isNull();
  }

  @Test
  @DisplayName("同步模型在没有业务上下文时补充用户级上下文")
  void chatModelOpensFallbackContext() {
    ChatModel delegate = mock(ChatModel.class);
    ChatRequest request = mock(ChatRequest.class);
    ChatResponse response = mock(ChatResponse.class);
    AtomicReference<LlmUsageContext.Context> captured = new AtomicReference<>();
    when(delegate.chat(request)).thenAnswer(invocation -> {
      captured.set(LlmUsageContext.current());
      return response;
    });

    ChatResponse actual = new UserScopedUsageChatModel(delegate, 9L).chat(request);

    assertThat(actual).isSameAs(response);
    assertThat(captured.get().userId()).isEqualTo(9L);
    assertThat(captured.get().operation())
        .isEqualTo(UserScopedUsageChatModel.DEFAULT_OPERATION);
  }

  @Test
  @DisplayName("同步模型不覆盖岗位报告等细粒度业务上下文")
  void chatModelPreservesExplicitContext() {
    ChatModel delegate = mock(ChatModel.class);
    ChatRequest request = mock(ChatRequest.class);
    AtomicReference<LlmUsageContext.Context> captured = new AtomicReference<>();
    when(delegate.chat(request)).thenAnswer(invocation -> {
      captured.set(LlmUsageContext.current());
      return mock(ChatResponse.class);
    });

    try (var ignored = LlmUsageContext.open(9L, "session-1", "report-1", "REPORT")) {
      new UserScopedUsageChatModel(delegate, 9L).chat(request);
      assertThat(captured.get()).isSameAs(LlmUsageContext.current());
      assertThat(captured.get().operation()).isEqualTo("REPORT");
      assertThat(captured.get().sessionId()).isEqualTo("session-1");
    }
  }

  @Test
  @DisplayName("流式模型启动请求时补充用户级上下文")
  void streamingModelOpensFallbackContext() {
    StreamingChatModel delegate = mock(StreamingChatModel.class);
    ChatRequest request = mock(ChatRequest.class);
    StreamingChatResponseHandler handler = mock(StreamingChatResponseHandler.class);
    AtomicReference<LlmUsageContext.Context> captured = new AtomicReference<>();
    doAnswer(invocation -> {
      captured.set(LlmUsageContext.current());
      return null;
    }).when(delegate).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    new UserScopedUsageStreamingChatModel(delegate, 11L).chat(request, handler);

    assertThat(captured.get().userId()).isEqualTo(11L);
    assertThat(captured.get().operation())
        .isEqualTo(UserScopedUsageStreamingChatModel.DEFAULT_OPERATION);
  }
}
