package com.linrun.interview.common.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.linrun.interview.common.config.LlmProviderProperties.AdvisorConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SafeGuard prompt 注入防御代理（流式版）。
 *
 * <p>流式对应物 of {@link SafeGuardChatModel}。LC4j 的 {@link ChatModelListener} 是只读观察接口，
 * 无法短路请求，因此用代理包装 {@link StreamingChatModel}：在 {@link #chat(ChatRequest, StreamingChatResponseHandler)}
 * 入口检查 UserMessage 文本是否命中配置的敏感词，命中则直接向 handler 回灌固定拒绝语并完成，
 * 不调用底层 LLM；否则委托给 delegate。检查范围限定 UserMessage，与同步版行为一致。
 *
 * <p>短路时按"完整拒绝语一次性回流"模拟流式语义：先 onPartialResponse 再 onCompleteResponse，
 * 让上层 Flux 桥接能正常拿到单段文本并结束，无需特殊处理。
 */
@Slf4j
public class SafeGuardStreamingChatModel implements StreamingChatModel {

    private static final String REFUSAL = "抱歉，我只能协助面试相关的任务。";

    private final StreamingChatModel delegate;
    private final List<String> sensitiveWords;
    private final boolean enabled;

    public SafeGuardStreamingChatModel(StreamingChatModel delegate, AdvisorConfig advisorConfig) {
        this.delegate = delegate;
        this.enabled = advisorConfig != null && advisorConfig.isSafeguardEnabled();
        this.sensitiveWords = (advisorConfig != null && advisorConfig.getSafeguardWords() != null)
            ? new ArrayList<>(advisorConfig.getSafeguardWords())
            : List.of();
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        if (enabled && containsSensitiveWord(chatRequest)) {
            log.warn("[SafeGuardStreamingChatModel] 检测到 prompt 注入特征词，已短路流式请求并返回拒绝语");
            shortCircuitRefusal(handler);
            return;
        }
        delegate.chat(chatRequest, handler);
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        if (enabled && containsSensitiveWord(chatRequest)) {
            shortCircuitRefusal(handler);
            return;
        }
        delegate.doChat(chatRequest, handler);
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
    public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
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

    private void shortCircuitRefusal(StreamingChatResponseHandler handler) {
        handler.onPartialResponse(REFUSAL);
        AiMessage refusal = AiMessage.from(REFUSAL);
        handler.onCompleteResponse(ChatResponse.builder()
            .aiMessage(refusal)
            .metadata(ChatResponseMetadata.builder()
                .id("safeguard-blocked")
                .build())
            .build());
    }
}
