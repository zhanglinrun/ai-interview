package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 会话标题生成服务（亮点6，通用不挑领域）。
 *
 * <p>LangChain4j {@code AiServices} 接口，根据用户的首问生成简洁中文会话标题，替代知识库名规则拼接。
 * prompt 沿用 know-engine（不挑领域），由 {@code RagChatSessionService} 在首问流式完成后用虚拟线程
 * 异步触发，失败保留原规则标题。
 */
public interface TitleSummaryService {

    @SystemMessage("你是一个对话标题生成助手。根据用户的第一句话，生成一个简洁的中文会话标题，"
        + "要求：不超过20个字，不加引号，直接输出标题内容。")
    @UserMessage("请根据以下用户问题生成会话标题：{{it}}")
    String generateTitle(String userQuestion);
}
