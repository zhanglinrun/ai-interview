package com.linrun.interview.rag.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 知识库 RAG 问答 AiServices 接口（参考业界实现的 ChatAiService）。
 *
 * <p>由 {@code AiServices.builder} 构建，挂载 {@code retrievalAugmentor}（改写→路由→检索→融合→rerank→注入）
 * 与 {@code chatMemoryProvider}（多轮上下文），由 LC4j 自动编排 RAG 管线后调 LLM 生成。
 *
 * <p>系统提示在 builder 的 {@code .systemMessage(...)} 动态注入（含引用标注指令），
 * 故接口方法只声明 {@code @MemoryId} 会话标识与 {@code @UserMessage} 用户问题。
 */
public interface InterviewQueryAiService {

    /**
     * 流式问答，逐 token 返回（SSE）。
     *
     * @param sessionId 会话标识（供 chatMemory 取历史）
     * @param message   用户问题
     * @return token Flux
     */
    Flux<String> streamChat(@MemoryId String sessionId, @UserMessage String message);

    /**
     * 同步问答，返回完整回答。
     *
     * @param sessionId 会话标识
     * @param message   用户问题
     * @return 完整回答文本
     */
    String chat(@MemoryId String sessionId, @UserMessage String message);
}
