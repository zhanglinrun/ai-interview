package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 意图识别服务（亮点4，面试领域通用二分类版）。
 *
 * <p>LangChain4j {@code AiServices} 接口，判断用户问题是否与面试准备 / 技术知识 / 编程 / 简历 /
 * 职业规划 / 求职等相关。不相关的问题由调用方走 {@code CommonChatService} 通用对话兜底（不检索知识库），
 * 避免越界问题强行检索导致幻觉或"未检索到"。
 *
 * <p><b>取结构弃内容</b>：know-engine 用 204 行汽车领域 7 意图分类 prompt，本项目改为面试领域通用
 * 二分类（related / reason），不做意图细分，避免过度工程。system prompt 从 classpath
 * {@code prompts/intent-recognition.st} 加载。
 *
 * <p>返回 {@link IntentRecognitionResult}（LangChain4j Structured Output + {@code @JsonPropertyDescription}）。
 *
 * <p>无状态单轮，弃 know-engine 的 {@code @MemoryId} + ChatMemory（意图识别不需要历史记忆）。
 */
public interface IntentRecognitionService {

    @SystemMessage(fromResource = "prompts/intent-recognition.st")
    @UserMessage("{{it}}")
    IntentRecognitionResult recognize(String question);
}
