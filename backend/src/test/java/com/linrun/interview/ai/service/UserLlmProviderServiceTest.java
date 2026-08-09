package com.linrun.interview.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.ai.dto.SaveMyProviderRequest;
import com.linrun.interview.ai.mapper.UserLlmProviderMapper;
import com.linrun.interview.ai.entity.UserLlmProviderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserLlmProviderService 保存「我的模型」")
class UserLlmProviderServiceTest {

  @Mock private UserLlmProviderMapper userLlmProviderMapper;
  @Mock private ApiKeyEncryptionService encryptionService;
  @Mock private LlmProviderRegistry registry;

  @InjectMocks private UserLlmProviderService service;

  private static final Long USER_ID = 1L;

  private UserLlmProviderEntity existing() {
    return UserLlmProviderEntity.builder()
        .userId(USER_ID)
        .baseUrl("https://old.example.com/v1")
        .apiKeyCiphertext("old-cipher")
        .apiKeyNonce("old-nonce")
        .chatModel("old-model")
        .temperature(0.2)
        .build();
  }

  @Nested
  @DisplayName("首次配置（无记录）")
  class FirstTime {

    @Test
    @DisplayName("留空 API Key 时抛 BAD_REQUEST，不落库、不清缓存")
    void blankKeyRejected() {
      when(userLlmProviderMapper.selectById(USER_ID)).thenReturn(null);

      assertThatThrownBy(() -> service.saveMine(USER_ID,
          new SaveMyProviderRequest("https://api.example.com/v1", "  ", "gpt-test", null)))
          .isInstanceOfSatisfying(BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

      verify(userLlmProviderMapper, never()).insert(any(UserLlmProviderEntity.class));
      verify(registry, never()).evictUser(any());
    }

    @Test
    @DisplayName("填了 API Key 时加密后 insert 并清该用户缓存")
    void insertsWithEncryptedKey() {
      when(userLlmProviderMapper.selectById(USER_ID)).thenReturn(null);
      when(encryptionService.encrypt("sk-new"))
          .thenReturn(new ApiKeyEncryptionService.EncryptedValue("new-nonce", "new-cipher"));

      service.saveMine(USER_ID,
          new SaveMyProviderRequest("https://api.example.com/v1", "sk-new", "gpt-test", 0.3));

      ArgumentCaptor<UserLlmProviderEntity> captor =
          ArgumentCaptor.forClass(UserLlmProviderEntity.class);
      verify(userLlmProviderMapper).insert(captor.capture());
      assertThat(captor.getValue().getApiKeyCiphertext()).isEqualTo("new-cipher");
      assertThat(captor.getValue().getApiKeyNonce()).isEqualTo("new-nonce");
      verify(registry).evictUser(USER_ID);
    }
  }

  @Nested
  @DisplayName("更新已有配置")
  class Update {

    @Test
    @DisplayName("留空 API Key 时保留原密文，只更新 baseUrl/chatModel，不再次加密")
    void blankKeyPreservesCiphertext() {
      when(userLlmProviderMapper.selectById(USER_ID)).thenReturn(existing());

      service.saveMine(USER_ID,
          new SaveMyProviderRequest("https://new.example.com/v1", null, "new-model", 0.7));

      ArgumentCaptor<UserLlmProviderEntity> captor =
          ArgumentCaptor.forClass(UserLlmProviderEntity.class);
      verify(userLlmProviderMapper).updateById(captor.capture());
      UserLlmProviderEntity saved = captor.getValue();
      assertThat(saved.getApiKeyCiphertext()).isEqualTo("old-cipher");
      assertThat(saved.getApiKeyNonce()).isEqualTo("old-nonce");
      assertThat(saved.getBaseUrl()).isEqualTo("https://new.example.com/v1");
      assertThat(saved.getChatModel()).isEqualTo("new-model");
      verify(encryptionService, never()).encrypt(anyString());
      verify(registry).evictUser(USER_ID);
    }

    @Test
    @DisplayName("填了新 API Key 时覆盖密文/nonce")
    void newKeyOverwritesCiphertext() {
      when(userLlmProviderMapper.selectById(USER_ID)).thenReturn(existing());
      when(encryptionService.encrypt("sk-rotated"))
          .thenReturn(new ApiKeyEncryptionService.EncryptedValue("rot-nonce", "rot-cipher"));

      service.saveMine(USER_ID,
          new SaveMyProviderRequest("https://old.example.com/v1", "sk-rotated", "old-model", 0.2));

      ArgumentCaptor<UserLlmProviderEntity> captor =
          ArgumentCaptor.forClass(UserLlmProviderEntity.class);
      verify(userLlmProviderMapper).updateById(captor.capture());
      assertThat(captor.getValue().getApiKeyCiphertext()).isEqualTo("rot-cipher");
      assertThat(captor.getValue().getApiKeyNonce()).isEqualTo("rot-nonce");
      verify(registry).evictUser(USER_ID);
    }
  }
}
