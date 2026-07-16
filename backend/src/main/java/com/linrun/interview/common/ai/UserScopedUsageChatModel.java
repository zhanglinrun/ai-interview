package com.linrun.interview.common.ai;

import com.linrun.interview.common.observability.LlmUsageContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/** 为用户 BYOK ChatModel 补充不会覆盖细粒度业务上下文的统一用量兜底。 */
final class UserScopedUsageChatModel implements ChatModel {

  static final String DEFAULT_OPERATION = "BYOK_CHAT";

  private final ChatModel delegate;
  private final Long userId;

  UserScopedUsageChatModel(ChatModel delegate, Long userId) {
    this.delegate = delegate;
    this.userId = userId;
  }

  ChatModel delegate() {
    return delegate;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    try (var ignored = LlmUsageContext.openIfAbsent(userId, DEFAULT_OPERATION)) {
      return delegate.chat(request);
    }
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    try (var ignored = LlmUsageContext.openIfAbsent(userId, DEFAULT_OPERATION)) {
      return delegate.doChat(request);
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
