package com.linrun.interview.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.common.config.LlmProviderProperties;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.llmprovider.mapper.UserLlmProviderMapper;
import com.linrun.interview.modules.llmprovider.model.UserLlmProviderEntity;
import com.linrun.interview.modules.llmprovider.service.ApiKeyEncryptionService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderRegistry 用户级 BYOK 解析")
class LlmProviderRegistryUserTest {

  @Mock private UserLlmProviderMapper userLlmProviderMapper;
  @Mock private ApiKeyEncryptionService encryptionService;

  private LlmProviderRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new LlmProviderRegistry(
        new LlmProviderProperties(),
        null,
        null,
        userLlmProviderMapper,
        encryptionService,
        List.of());
  }

  private UserLlmProviderEntity userEntity(Long userId, String chatModel) {
    return UserLlmProviderEntity.builder()
        .userId(userId)
        .baseUrl("https://api.example.com/v1")
        .apiKeyCiphertext("cipher-" + userId)
        .apiKeyNonce("nonce-" + userId)
        .chatModel(chatModel)
        .temperature(0.3)
        .build();
  }

  @Nested
  @DisplayName("已配置用户")
  class Configured {

    @Test
    @DisplayName("getUserChatModel 返回 SafeGuard 包装的 ChatModel")
    void returnsChatModelWhenConfigured() {
      when(userLlmProviderMapper.selectById(1L)).thenReturn(userEntity(1L, "gpt-test"));
      when(encryptionService.decrypt(any(), any())).thenReturn("sk-user-1");

      ChatModel model = registry.getUserChatModel(1L);

      assertThat(model).isInstanceOf(SafeGuardChatModel.class);
      verify(encryptionService).decrypt("nonce-1", "cipher-1");
    }

    @Test
    @DisplayName("getUserStreamingChatModel 返回 SafeGuard 流式模型")
    void returnsStreamingModelWhenConfigured() {
      when(userLlmProviderMapper.selectById(1L)).thenReturn(userEntity(1L, "gpt-test"));
      when(encryptionService.decrypt(any(), any())).thenReturn("sk-user-1");

      StreamingChatModel model = registry.getUserStreamingChatModel(1L);

      assertThat(model).isInstanceOf(SafeGuardStreamingChatModel.class);
    }

    @Test
    @DisplayName("重复获取同一用户模型命中缓存，仅加载一次")
    void cachesSameUser() {
      when(userLlmProviderMapper.selectById(1L)).thenReturn(userEntity(1L, "gpt-test"));
      when(encryptionService.decrypt(any(), any())).thenReturn("sk-user-1");

      ChatModel first = registry.getUserChatModel(1L);
      ChatModel second = registry.getUserChatModel(1L);

      assertThat(first).isSameAs(second);
      verify(userLlmProviderMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("不同用户各自独立缓存，互不串用")
    void cachesIndependentlyPerUser() {
      when(userLlmProviderMapper.selectById(1L)).thenReturn(userEntity(1L, "model-a"));
      when(userLlmProviderMapper.selectById(3L)).thenReturn(userEntity(3L, "model-b"));
      when(encryptionService.decrypt(any(), any())).thenReturn("sk-x");

      ChatModel userOne = registry.getUserChatModel(1L);
      ChatModel userThree = registry.getUserChatModel(3L);

      assertThat(userOne).isNotSameAs(userThree);
      verify(userLlmProviderMapper, times(1)).selectById(1L);
      verify(userLlmProviderMapper, times(1)).selectById(3L);
    }
  }

  @Nested
  @DisplayName("未配置用户")
  class NotConfigured {

    @Test
    @DisplayName("getUserChatModel 在无配置时抛 USER_LLM_NOT_CONFIGURED")
    void chatThrowsWhenAbsent() {
      when(userLlmProviderMapper.selectById(2L)).thenReturn(null);

      assertThatThrownBy(() -> registry.getUserChatModel(2L))
          .isInstanceOfSatisfying(BusinessException.class,
              e -> assertThat(e.getCode())
                  .isEqualTo(ErrorCode.USER_LLM_NOT_CONFIGURED.getCode()));
    }

    @Test
    @DisplayName("getUserStreamingChatModel 在无配置时抛 USER_LLM_NOT_CONFIGURED")
    void streamThrowsWhenAbsent() {
      when(userLlmProviderMapper.selectById(2L)).thenReturn(null);

      assertThatThrownBy(() -> registry.getUserStreamingChatModel(2L))
          .isInstanceOfSatisfying(BusinessException.class,
              e -> assertThat(e.getCode())
                  .isEqualTo(ErrorCode.USER_LLM_NOT_CONFIGURED.getCode()));
    }
  }

  @Nested
  @DisplayName("缓存失效")
  class Eviction {

    @Test
    @DisplayName("evictUser 清除后重新加载，不再命中旧缓存")
    void evictUserClearsCache() {
      when(userLlmProviderMapper.selectById(1L)).thenReturn(userEntity(1L, "gpt-test"));
      when(encryptionService.decrypt(any(), any())).thenReturn("sk-user-1");

      ChatModel before = registry.getUserChatModel(1L);
      registry.evictUser(1L);
      ChatModel after = registry.getUserChatModel(1L);

      assertThat(after).isNotSameAs(before);
      verify(userLlmProviderMapper, times(2)).selectById(1L);
    }
  }
}
