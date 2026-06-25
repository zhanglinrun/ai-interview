package interview.guide.common.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.data.message.AiMessage;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeGuard prompt 注入防御代理（LangChain4j 版）。
 *
 * <p>替代 Spring AI 的 SafeGuardAdvisor。LC4j 的 {@link dev.langchain4j.model.chat.listener.ChatModelListener}
 * 是只读观察接口，无法短路请求，因此用代理包装 {@link ChatModel}：在 {@link #chat(ChatRequest)}
 * 入口检查 UserMessage 文本是否命中配置的敏感词，命中则直接返回固定拒绝语，不调用底层 LLM，
 * 否则委托给 delegate。所有 {@code chat(String)}/{@code chat(ChatMessage...)}/{@code chat(List)}
 * default 方法最终汇聚到 {@link #chat(ChatRequest)}，被统一拦截。
 *
 * <p>检查范围限定 UserMessage（与原 SafeGuardAdvisor 行为一致）。
 */
@Slf4j
public class SafeGuardChatModel implements ChatModel {

    private static final String REFUSAL = "抱歉，我只能协助面试相关的任务。";

    private final ChatModel delegate;
    private final List<String> sensitiveWords;
    private final boolean enabled;

    public SafeGuardChatModel(ChatModel delegate, AdvisorConfig advisorConfig) {
        this.delegate = delegate;
        this.enabled = advisorConfig != null && advisorConfig.isSafeguardEnabled();
        this.sensitiveWords = (advisorConfig != null && advisorConfig.getSafeguardWords() != null)
            ? new ArrayList<>(advisorConfig.getSafeguardWords())
            : List.of();
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        if (enabled && containsSensitiveWord(chatRequest)) {
            log.warn("[SafeGuardChatModel] 检测到 prompt 注入特征词，已短路请求并返回拒绝语");
            return refusalResponse(chatRequest);
        }
        return delegate.chat(chatRequest);
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        if (enabled && containsSensitiveWord(chatRequest)) {
            return refusalResponse(chatRequest);
        }
        return delegate.doChat(chatRequest);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    private boolean containsSensitiveWord(ChatRequest chatRequest) {
        if (sensitiveWords.isEmpty() || chatRequest == null || chatRequest.messages() == null) {
            return false;
        }
        for (ChatMessage message : chatRequest.messages()) {
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            String text = userMessage.singleText();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String word : sensitiveWords) {
                if (word != null && !word.isBlank() && text.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ChatResponse refusalResponse(ChatRequest chatRequest) {
        AiMessage refusal = AiMessage.from(REFUSAL);
        return ChatResponse.builder()
            .aiMessage(refusal)
            .metadata(ChatResponseMetadata.builder()
                .id("safeguard-blocked")
                .build())
            .build();
    }
}
