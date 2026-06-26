package com.linrun.interview.common.ai;

import com.linrun.interview.common.config.LlmProviderProperties;
import com.linrun.interview.common.config.LlmProviderProperties.ProviderConfig;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.linrun.interview.modules.llmprovider.model.LlmProviderEntity;
import com.linrun.interview.modules.llmprovider.repository.LlmGlobalSettingRepository;
import com.linrun.interview.modules.llmprovider.repository.LlmProviderRepository;
import com.linrun.interview.modules.llmprovider.service.ApiKeyEncryptionService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing and caching LLM providers.
 *
 * <p>LangChain4j 版本：按 providerId 路由创建并缓存 {@link ChatModel} /
 * {@link StreamingChatModel} / {@link EmbeddingModel}。各 Provider 配置优先从 DB 读取
 * （{@code LlmProviderRepository} + {@link ApiKeyEncryptionService} 解密），DB 为空时回退到
 * {@link LlmProviderProperties#getProviders()}。
 *
 * <p>Advisor 体系（SafeGuard prompt 注入防御、SkillsTool、ChatMemory）在阶段 3 由
 * {@code ChatModelListener} / {@code AiServices} 重建，本类只负责底层模型实例的创建与缓存。
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingChatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Get a {@link ChatModel} for the specified provider ID (cached).
     */
    public ChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return createChatModel(id);
        });
    }

    /**
     * Get the default {@link ChatModel} based on app.ai.default-provider.
     */
    public ChatModel getDefaultChatModel() {
        return getChatModel(resolveDefaultChatProviderId());
    }

    /**
     * Get a {@link ChatModel} for the specified provider, falling back to the default if null or blank.
     */
    public ChatModel getChatModelOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatModel(providerId);
        }
        return getDefaultChatModel();
    }

    /**
     * 获取流式 {@link StreamingChatModel}，用于 SSE 场景（知识库问答、语音面试实时字幕）。
     */
    public StreamingChatModel getStreamingChatModel(String providerId) {
        String id = resolveProviderId(providerId);
        return streamingChatModelCache.computeIfAbsent(id, key -> {
            log.info("[LlmProviderRegistry] Creating new StreamingChatModel for provider: {}", key);
            return createStreamingChatModel(key);
        });
    }

    public StreamingChatModel getDefaultStreamingChatModel() {
        return getStreamingChatModel(resolveDefaultChatProviderId());
    }

    public StreamingChatModel getStreamingChatModelOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getStreamingChatModel(providerId);
        }
        return getDefaultStreamingChatModel();
    }

    /**
     * 清空缓存，重新加载所有 provider。
     */
    public void reload() {
        int size = chatModelCache.size() + streamingChatModelCache.size() + embeddingModelCache.size();
        chatModelCache.clear();
        streamingChatModelCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create models.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    private ChatModel createChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        ChatModel raw = OpenAiChatModel.builder()
                .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
                .apiKey(config.apiKey())
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .modelName(config.model())
                        .temperature(config.temperature() != null ? config.temperature() : 0.2)
                        .build())
                .maxRetries(1)
                .build();
        return new SafeGuardChatModel(raw, properties.getAdvisors());
    }

    private StreamingChatModel createStreamingChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building StreamingChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        StreamingChatModel raw = OpenAiStreamingChatModel.builder()
                .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
                .apiKey(config.apiKey())
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .modelName(config.model())
                        .temperature(config.temperature() != null ? config.temperature() : 0.2)
                        .build())
                .build();
        return new SafeGuardStreamingChatModel(raw, properties.getAdvisors());
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        return OpenAiEmbeddingModel.builder()
            .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
            .apiKey(config.apiKey())
            .modelName(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .maxRetries(1)
            .build();
    }

    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
            ? providerId : resolveDefaultChatProviderId();
    }

    private String resolveDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider());
    }

    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                "LLM Provider 不存在或未启用: " + providerId));
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "LLM Provider 不存在: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature
    ) {
    }
}
