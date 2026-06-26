package com.linrun.interview.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private String defaultEmbeddingProvider;
    private Integer embeddingDimensions = 1024;
    private Map<String, ProviderConfig> providers;
    private AdvisorConfig advisors = new AdvisorConfig();
    private String configYamlPath;
    private String configEnvPath;
    private SecurityConfig security = new SecurityConfig();
    private ThinkingConfig thinking = new ThinkingConfig();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private Boolean supportsEmbedding;
        private Double temperature;
    }

    @Data
    public static class SecurityConfig {
        private String apiKeyEncryptionKey;
        private boolean requireEncryptionKey = false;
    }

    /**
     * LLM 思考模式（reasoning）开关。DashScope 的 qwen3.5-flash 等模型默认开启 thinking，
     * 会对查询改写等简单任务产生上千 token 的思考过程，导致单次调用 16-40 秒。
     * 默认关闭 thinking（通过 OpenAI 兼容接口的 enable_thinking=false 透传），
     * 显著降低延迟；需要深度推理的场景可在 application.yml 设 app.ai.thinking.enabled=true 开启。
     * 非 reasoning 模型会忽略该参数，无副作用。
     */
    @Data
    public static class ThinkingConfig {
        private boolean enabled = false;
    }

    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;

        // ToolCallAdvisor
        private boolean toolCallEnabled = true;
        private boolean toolCallConversationHistoryEnabled = false;
        private boolean streamToolCallResponses = false;

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        private boolean messageChatMemoryEnabled = false;
        private int messageChatMemoryMaxMessages = 120;

        // SimpleLoggerAdvisor（默认关闭）
        private boolean simpleLoggerEnabled = false;

        // SafeGuardAdvisor
        private boolean safeguardEnabled = true;
        private List<String> safeguardWords = List.of(
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        );

        // PromptSanitizer
        private boolean promptSanitizerEnabled = true;
    }
}
