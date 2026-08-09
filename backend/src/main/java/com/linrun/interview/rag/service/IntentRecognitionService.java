package com.linrun.interview.rag.service;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.chat.service.CommonChatService;


import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 意图识别服务（亮点4，面试领域任务路由版）。
 *
 * <p>面向调用方的稳定接口。实现层用三路融合：LLM 语义识别、样例向量相似度、关键词规则兜底，
 * 输出综合置信度和各路证据，用于后续 RAG 路由、Prompt 选择和离题兜底。
 *
 * <p>不相关的问题由调用方走 {@code CommonChatService} 通用对话兜底（不检索知识库），避免越界问题
 * 强行检索导致幻觉或"未检索到"。
 *
 * <p>无历史调用保留给调试端点；带历史调用会把最近上下文纳入缓存 key，避免同一句话在不同对话里
 * 复用错误意图。
 */
public interface IntentRecognitionService {

    IntentRecognitionResult recognize(String question);

    default IntentRecognitionResult recognize(String question, List<ChatMessage> history) {
        return recognize(question);
    }
}
