package com.linrun.interview.ai.config;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.rag.service.LlmIntentRecognitionAiService;
import com.linrun.interview.ai.service.TitleSummaryService;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 知识库 RAG AiServices Bean 配置（亮点4 / 亮点6）。
 */
@Configuration
@DependsOn("llmProviderBootstrapService")
@Slf4j
public class KnowledgeBaseAiServicesConfig {

    @Bean
    public LlmIntentRecognitionAiService llmIntentRecognitionAiService(
        LlmProviderRegistry llmProviderRegistry,
        KnowledgeBaseQueryProperties queryProperties) {
        String intentModel = queryProperties.resolveDecisionModel(
            queryProperties.getIntentRecognition().getModel());
        log.info("[KnowledgeBaseAiServices] 构造 LlmIntentRecognitionAiService, model={}", intentModel);
        return AiServices.builder(LlmIntentRecognitionAiService.class)
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
