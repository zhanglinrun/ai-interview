package com.linrun.interview.ai.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.service.output.JsonSchemas;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略（LangChain4j 版）。
 *
 * <p>替代 Spring AI 的 ChatClient + BeanOutputConverter 组合：通过
 * {@link JsonSchemas#jsonSchemaFrom(Type)} 从目标 Java 类型生成 LC4j
 * {@link JsonSchema}，以 {@link ChatRequestParameters#responseFormat} 传给
 * {@link ChatModel#chat(ChatRequest)}，拿到 LLM 返回的 JSON 文本后用 Jackson
 * 反序列化为目标类型。保留原有的重试、未转义引号本地修复、Micrometer 指标能力。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        ObjectMapper objectMapper,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.meterRegistry = meterRegistry;
    }

    /**
     * 调用 LLM 并把返回的 JSON 解析为目标类型 {@code T}，带重试与本地 JSON 修复。
     *
     * @param chatModel              LangChain4j ChatModel（通常经 LlmProviderRegistry 获取）
     * @param systemPromptWithFormat system prompt（已含 JSON 格式约束；防御指令会自动追加）
     * @param userPrompt             用户 prompt
     * @param targetType             目标 Java 类型（Class、ParameterizedType 或 TypeReference）
     * @param errorCode              解析失败兜底错误码
     * @param errorPrefix            错误前缀描述
     * @param logContext             日志上下文标签
     * @param log                    调用方 logger
     * @return 解析后的目标类型对象
     */
    public <T> T invoke(
        ChatModel chatModel,
        String systemPromptWithFormat,
        String userPrompt,
        Type targetType,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        JsonSchema jsonSchema = resolveJsonSchema(targetType, logContext, log);
        // 把 Schema 以文本形式注入首轮 system prompt：部分 OpenAI 兼容网关（如 DashScope）
        // 不支持 response_format=json_schema 会降级成 json_object（schema 被丢弃，字段名靠模型自造），
        // 且要求 messages 中必须出现 "json" 字样，否则直接 400
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
            + buildSchemaInstruction(jsonSchema, logContext, log);
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                ChatRequest request = ChatRequest.builder()
                    .messages(
                        SystemMessage.from(attemptSystemPrompt),
                        UserMessage.from(userPrompt))
                    .parameters(ChatRequestParameters.builder()
                        .responseFormat(jsonSchema)
                        .build())
                    .build();
                AiMessage aiMessage = chatModel.chat(request).aiMessage();
                String content = aiMessage != null ? aiMessage.text() : null;
                T result = convertWithRepair(content, targetType, logContext, log);
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage(), e);
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage(), e);
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown"),
            lastError
        );
    }

    /**
     * 把 JsonSchema 渲染成文本追加到 system prompt。双重目的：
     * 1) 满足 DashScope 等网关「json_object 模式下 messages 必须含 'json' 字样」的硬校验；
     * 2) json_object 模式不带 schema 约束，字段名全靠 prompt 文本锚定，注入后模型才能输出正确字段。
     */
    private String buildSchemaInstruction(JsonSchema jsonSchema, String logContext, Logger log) {
        try {
            String schemaJson = objectMapper.writeValueAsString(
                JsonSchemaElementUtils.toMap(jsonSchema.rootElement()));
            return "\n\n# 输出格式（必须严格遵守）\n"
                + "请仅输出一个符合下面 JSON Schema 的 JSON 对象，字段名必须与 Schema 完全一致"
                + "（不得增删字段、不得改名、不得使用 snake_case 变体），"
                + "不要输出 Markdown 代码块或任何解释文字：\n"
                + schemaJson;
        } catch (Exception e) {
            log.warn("{}JSON Schema 文本化失败，回退到通用 JSON 指令", logContext, e);
            return "\n\n" + STRICT_JSON_INSTRUCTION;
        }
    }

    private JsonSchema resolveJsonSchema(Type targetType, String logContext, Logger log) {
        Optional<JsonSchema> schema = JsonSchemas.jsonSchemaFrom(targetType);
        if (schema.isEmpty()) {
            throw new BusinessException(
                ErrorCode.AI_SERVICE_ERROR,
                "无法为目标类型生成 JSON Schema: " + (targetType != null ? targetType.getTypeName() : "null")
            );
        }
        log.debug("{}生成 JSON Schema: type={}", logContext,
            targetType != null ? targetType.getTypeName() : "null");
        return schema.get();
    }

    private <T> T convertWithRepair(
        String content,
        Type targetType,
        String logContext,
        Logger log
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM 返回内容为空，无法解析为结构化对象");
        }
        JavaType javaType = objectMapper.constructType(targetType);
        try {
            return objectMapper.readValue(content, javaType);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = objectMapper.readValue(repaired, javaType);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw new BusinessException(
                ErrorCode.AI_SERVICE_ERROR,
                "结构化 JSON 解析失败: " + firstError.getMessage(),
                firstError
            );
        }
    }

    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
