package com.linrun.interview.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.config.LlmProviderProperties;
import com.linrun.interview.config.LlmProviderProperties.ProviderConfig;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.ai.mapper.LlmGlobalSettingMapper;
import com.linrun.interview.ai.mapper.LlmProviderMapper;
import com.linrun.interview.ai.entity.LlmGlobalSettingEntity;
import com.linrun.interview.ai.entity.LlmProviderEntity;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private final LlmProviderProperties properties;
  private final LlmProviderMapper providerMapper;
  private final LlmGlobalSettingMapper globalSettingMapper;
  private final ApiKeyEncryptionService encryptionService;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    if (providerMapper.selectCount(null) == 0) {
      seedProviders();
    }
    ensureGlobalSetting();
  }

  private void seedProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    providers.forEach((id, config) -> {
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());

      LocalDateTime now = LocalDateTime.now();
      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)
          .baseUrl(config.getBaseUrl())
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(config.getModel())
          .embeddingModel(trimOrNull(config.getEmbeddingModel()))
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
          .supportsEmbedding(supportsEmbedding)
          .temperature(config.getTemperature())
          .enabled(true)
          .builtin(true)
          .createdAt(now)
          .updatedAt(now)
          .build();
      providerMapper.insert(entity);
    });
    log.info("Seeded {} LLM providers from application configuration", providerMapper.selectCount(null));
  }

  private void ensureGlobalSetting() {
    if (globalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID) != null) {
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerMapper.selectList(null).stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    LocalDateTime now = LocalDateTime.now();
    // 必须直接 insert：MapperUtils.save 见到非空 id 会走 updateById，
    // 对不存在的单例行 update 影响 0 行且不报错，导致全局配置永远初始化不出来
    globalSettingMapper.insert(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .createdAt(now)
        .updatedAt(now)
        .build());
    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerMapper.selectById(preferredProvider) != null) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return Optional.ofNullable(providerMapper.selectById(preferredProvider))
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerMapper.selectList(
                Wrappers.<LlmProviderEntity>lambdaQuery().eq(LlmProviderEntity::isEnabled, true))
            .stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
