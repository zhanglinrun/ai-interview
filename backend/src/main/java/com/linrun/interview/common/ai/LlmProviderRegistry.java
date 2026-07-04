package com.linrun.interview.common.ai;

import com.linrun.interview.common.config.LlmProviderProperties;
import com.linrun.interview.common.config.LlmProviderProperties.ProviderConfig;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.linrun.interview.modules.llmprovider.model.LlmProviderEntity;
import com.linrun.interview.modules.llmprovider.mapper.LlmGlobalSettingMapper;
import com.linrun.interview.modules.llmprovider.mapper.LlmProviderMapper;
import com.linrun.interview.modules.llmprovider.service.ApiKeyEncryptionService;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final LlmProviderMapper llmProviderMapper;
    private final LlmGlobalSettingMapper llmGlobalSettingMapper;
    private final ApiKeyEncryptionService encryptionService;
    /** 全局 ChatModel 监听器（如 Langfuse generation 采集）；无则空列表。 */
    private final List<ChatModelListener> chatModelListeners;

    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v4",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderMapper llmProviderMapper,
            LlmGlobalSettingMapper llmGlobalSettingMapper,
            ApiKeyEncryptionService encryptionService,
            List<ChatModelListener> chatModelListeners) {
        this.properties = properties;
        this.llmProviderMapper = llmProviderMapper;
        this.llmGlobalSettingMapper = llmGlobalSettingMapper;
        this.encryptionService = encryptionService;
        this.chatModelListeners = chatModelListeners == null ? List.of() : chatModelListeners;
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
        if (isEffectiveProviderId(providerId)) {
            return getChatModel(providerId);
        }
        return getDefaultChatModel();
    }

    /**
     * 按指定模型名获取 {@link ChatModel}（用于意图识别等分场景模型）。
     */
    public ChatModel getChatModelWithModel(String providerId, String modelName) {
        if (isBlank(modelName)) {
            return getChatModelOrDefault(providerId);
        }
        String cacheKey = resolveProviderId(providerId) + "::chat::" + modelName;
        return chatModelCache.computeIfAbsent(cacheKey,
            key -> createChatModel(resolveProviderId(providerId), modelName));
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
        if (isEffectiveProviderId(providerId)) {
            return getStreamingChatModel(providerId);
        }
        return getDefaultStreamingChatModel();
    }

    /**
     * 会话持久化层会把「未指定 Provider」存成字面量 "default"（见 InterviewPersistenceService），
     * 这里统一视为走默认 Provider，避免被当成真实 providerId 去 DB 查找而报「Provider 不存在」。
     */
    private boolean isEffectiveProviderId(String providerId) {
        return providerId != null && !providerId.isBlank() && !"default".equalsIgnoreCase(providerId);
    }

    /**
     * 按指定模型名获取 {@link StreamingChatModel}（用于 RAG 流式生成，对齐业界实践 ragChatModel）。
     */
    public StreamingChatModel getStreamingChatModelWithModel(String providerId, String modelName) {
        if (isBlank(modelName)) {
            return getStreamingChatModelOrDefault(providerId);
        }
        String cacheKey = resolveProviderId(providerId) + "::stream::" + modelName;
        return streamingChatModelCache.computeIfAbsent(cacheKey,
            key -> createStreamingChatModel(resolveProviderId(providerId), modelName));
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
        return createChatModel(config, config.model());
    }

    private ChatModel createChatModel(String providerId, String modelName) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        return createChatModel(config, modelName);
    }

    private ChatModel createChatModel(ProviderSnapshot config, String modelName) {
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}, thinking={}",
            config.id(), config.baseUrl(), modelName, properties.getThinking().isEnabled());

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            // classpath 同时存在 spring-restclient 与 jdk 两个 HTTP client SPI，显式指定避免启动冲突
            .httpClientBuilder(JdkHttpClient.builder())
            .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
            .apiKey(config.apiKey())
            .defaultRequestParameters(buildChatRequestParameters(config, modelName))
            .maxRetries(1);
        if (!chatModelListeners.isEmpty()) {
            builder.listeners(chatModelListeners);
        }
        return new SafeGuardChatModel(builder.build(), properties.getAdvisors());
    }

    private StreamingChatModel createStreamingChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        return createStreamingChatModel(config, config.model());
    }

    private StreamingChatModel createStreamingChatModel(String providerId, String modelName) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        return createStreamingChatModel(config, modelName);
    }

    private StreamingChatModel createStreamingChatModel(ProviderSnapshot config, String modelName) {
        log.info("[LlmProviderRegistry] Building StreamingChatModel - Provider: {}, BaseUrl: {}, Model: {}, thinking={}",
            config.id(), config.baseUrl(), modelName, properties.getThinking().isEnabled());

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
            .httpClientBuilder(JdkHttpClient.builder())
            .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
            .apiKey(config.apiKey())
            .defaultRequestParameters(buildChatRequestParameters(config, modelName));
        if (!chatModelListeners.isEmpty()) {
            builder.listeners(chatModelListeners);
        }
        return new SafeGuardStreamingChatModel(builder.build(), properties.getAdvisors());
    }

    /**
     * 构造 OpenAiChatRequestParameters，含模型名、温度以及（默认关闭的）thinking 开关。
     * <p>DashScope 的 qwen3.5-flash 等 reasoning 模型默认开启 thinking，对查询改写、评估等
     * 简单任务会先生成上千 token 的思考过程，导致单次调用 16-40 秒。通过 customParameters
     * 透传 {@code enable_thinking=false} 关闭思考；非 reasoning 模型会忽略该参数，无副作用。
     * 如需深度推理，可在 application.yml 设 {@code app.ai.thinking.enabled=true}。
     */
    private OpenAiChatRequestParameters buildChatRequestParameters(ProviderSnapshot config) {
        return buildChatRequestParameters(config, config.model());
    }

    private OpenAiChatRequestParameters buildChatRequestParameters(ProviderSnapshot config, String modelName) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder()
            .modelName(modelName)
            .temperature(config.temperature() != null ? config.temperature() : 0.2);
        if (!properties.getThinking().isEnabled()) {
            builder.customParameters(Map.of("enable_thinking", false));
        }
        return builder.build();
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
            .httpClientBuilder(JdkHttpClient.builder())
            .baseUrl(ApiPathResolver.resolveBaseUrl(config.baseUrl()))
            .apiKey(config.apiKey())
            .modelName(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .maxRetries(1)
            .build();
    }

    private String resolveProviderId(String providerId) {
        return isEffectiveProviderId(providerId) ? providerId : resolveDefaultChatProviderId();
    }

    private String resolveDefaultChatProviderId() {
        if (llmGlobalSettingMapper == null) {
            return properties.getDefaultProvider();
        }
        return Optional.ofNullable(llmGlobalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID))
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

    private String resolveDefaultEmbeddingProviderId() {
        if (llmGlobalSettingMapper == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();
        }
        return Optional.ofNullable(llmGlobalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID))
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider());
    }

    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (llmProviderMapper == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = Optional.ofNullable(llmProviderMapper.selectById(providerId))
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
