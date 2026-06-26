package com.linrun.interview.modules.knowledgebase.config;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.TitleSummaryService;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库 RAG AiServices Bean 配置（亮点4 / 亮点6）。
 *
 * <p>把 {@link IntentRecognitionService}（意图识别兜底）与 {@link TitleSummaryService}（异步标题生成）
 * 构造为 Spring bean，底层 {@link dev.langchain4j.model.chat.ChatModel} 一律复用
 * {@link LlmProviderRegistry#getDefaultChatModel()}（弃 know-engine 每次 {@code new OpenAiChatModel}），
 * 支持多 Provider 路由、{@code enable_thinking=false} 已全局生效，无需各处再设。
 */
@Configuration
@Slf4j
public class KnowledgeBaseAiServicesConfig {

    @Bean
    public IntentRecognitionService intentRecognitionService(LlmProviderRegistry llmProviderRegistry) {
        log.info("[KnowledgeBaseAiServices] 构造 IntentRecognitionService（复用默认 ChatModel）");
        return AiServices.builder(IntentRecognitionService.class)
            .chatModel(llmProviderRegistry.getDefaultChatModel())
            .build();
    }

    @Bean
    public TitleSummaryService titleSummaryService(LlmProviderRegistry llmProviderRegistry) {
        log.info("[KnowledgeBaseAiServices] 构造 TitleSummaryService（复用默认 ChatModel）");
        return AiServices.builder(TitleSummaryService.class)
            .chatModel(llmProviderRegistry.getDefaultChatModel())
            .build();
    }
}
