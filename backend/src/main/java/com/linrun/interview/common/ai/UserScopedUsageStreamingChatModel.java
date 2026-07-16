package com.linrun.interview.common.ai;

import com.linrun.interview.common.observability.LlmUsageContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;

/** 为用户 BYOK StreamingChatModel 补充统一用量兜底。 */
final class UserScopedUsageStreamingChatModel implements StreamingChatModel {

  static final String DEFAULT_OPERATION = "BYOK_STREAM";

  private final StreamingChatModel delegate;
  private final Long userId;

  UserScopedUsageStreamingChatModel(StreamingChatModel delegate, Long userId) {
    this.delegate = delegate;
    this.userId = userId;
  }

  StreamingChatModel delegate() {
    return delegate;
  }

  @Override
  public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
    try (var ignored = LlmUsageContext.openIfAbsent(userId, DEFAULT_OPERATION)) {
      delegate.chat(request, handler);
    }
  }

  @Override
  public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
    try (var ignored = LlmUsageContext.openIfAbsent(userId, DEFAULT_OPERATION)) {
      delegate.doChat(request, handler);
    }
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public List<ChatModelListener> listeners() {
    return delegate.listeners();
  }

  @Override
  public ModelProvider provider() {
    return delegate.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }
}
