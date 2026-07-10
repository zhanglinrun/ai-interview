package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.ai.FluxStreamingBridge;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用对话兜底服务（亮点4，无 RAG）。
 *
 * <p>当意图识别判定问题与面试 / 技术知识 / 简历 / 求职等场景无关时，跳过知识库检索，直接走
 * {@link StreamingChatModel} 流式生成通用回答，避免越界问题强行检索导致幻觉或"未检索到"。
 *
 * <p>底层模型走用户 BYOK 的 {@link LlmProviderRegistry#getUserStreamingChatModel(Long)}（弃用早期
 * 每次 {@code new OpenAiChatModel}）。流式 token 经 {@link FluxStreamingBridge} 桥接为 {@code Flux<String>}，
 * 由调用方带 SSE 前缀协议推给前端（与 RAG 流同形，前端无需区分）。
 */
@Slf4j
@Service
public class CommonChatService {

    private static final String SYSTEM_PROMPT = """
        你是一个友好的助手。当用户的问题与平台的核心场景（面试准备、技术知识、编程、简历、职业规划、求职）
        相关时，尽力给出有帮助的回答；当确实无法回答或与这些场景无关时，如实说明并引导用户提出相关问题。
        回答使用中文，简洁清晰。""";

    private final LlmProviderRegistry llmProviderRegistry;

    public CommonChatService(LlmProviderRegistry llmProviderRegistry) {
        this.llmProviderRegistry = llmProviderRegistry;
    }

    /**
     * 流式通用对话（无 RAG），逐 token 返回。
     *
     * @param question 用户问题
     * @param userId   当前用户 ID（BYOK 路由，由 KnowledgeBaseQueryService 在请求线程捕获后传入）
     * @return token Flux
     */
    public Flux<String> streamChat(String question, Long userId) {
        StreamingChatModel streamingChatModel = llmProviderRegistry.getUserStreamingChatModel(userId);
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(question));
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        log.info("[CommonChatService] 走通用对话兜底（无 RAG）: questionLen={}",
            question != null ? question.length() : 0);
        return FluxStreamingBridge.stream(streamingChatModel, request);
    }
}
