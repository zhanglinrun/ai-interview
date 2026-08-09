package com.linrun.interview.infra.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j ChatMemory 的 Redis 持久化实现。
 *
 * <p>面试 Agent 的会话内窗口记忆（{@code MessageWindowChatMemory}）落 Redis，
 * 服务重启/多实例下会话上下文不丢；key 按 memoryId（面试 sessionId）隔离，
 * TTL 与面试会话缓存一致（24h）。序列化用 LC4j 官方 JSON 工具，兼容工具调用消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

  private static final String KEY_PREFIX = "interview:agent:memory:";
  private static final Duration MEMORY_TTL = Duration.ofHours(24);

  private final RedisService redisService;

  @Override
  public List<ChatMessage> getMessages(Object memoryId) {
    String json = redisService.get(buildKey(memoryId));
    if (json == null || json.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    } catch (Exception e) {
      log.warn("反序列化 ChatMemory 失败，按空记忆处理: memoryId={}", memoryId, e);
      return new ArrayList<>();
    }
  }

  @Override
  public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    redisService.set(buildKey(memoryId), ChatMessageSerializer.messagesToJson(messages), MEMORY_TTL);
  }

  @Override
  public void deleteMessages(Object memoryId) {
    redisService.delete(buildKey(memoryId));
  }

  private String buildKey(Object memoryId) {
    return KEY_PREFIX + memoryId;
  }
}
