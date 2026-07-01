package com.linrun.interview.modules.knowledgebase.config;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.TitleSummaryService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库 RAG AiServices Bean 配置（亮点4 / 亮点6）。
 */
@Configuration
@Slf4j
public class KnowledgeBaseAiServicesConfig {

    @Bean
    public IntentRecognitionService intentRecognitionService(
        LlmProviderRegistry llmProviderRegistry,
        KnowledgeBaseQueryProperties queryProperties) {
        String intentModel = queryProperties.getIntentRecognition().getModel();
        log.info("[KnowledgeBaseAiServices] 构造 IntentRecognitionService, model={}",
            intentModel == null || intentModel.isBlank() ? "default" : intentModel);
        return AiServices.builder(IntentRecognitionService.class)
            .chatModel(llmProviderRegistry.getChatModelWithModel(null, intentModel))
            .build();
    }

    @Bean
    public TitleSummaryService titleSummaryService(
        LlmProviderRegistry llmProviderRegistry,
        KnowledgeBaseQueryProperties queryProperties) {
        String titleModel = queryProperties.getTitleSummary().getModel();
        log.info("[KnowledgeBaseAiServices] 构造 TitleSummaryService, model={}", titleModel);
        return AiServices.builder(TitleSummaryService.class)
            .chatModel(llmProviderRegistry.getChatModelWithModel(null, titleModel))
            .build();
    }
}
