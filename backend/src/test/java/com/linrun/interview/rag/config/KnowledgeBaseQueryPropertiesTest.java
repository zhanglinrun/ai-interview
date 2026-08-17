package com.linrun.interview.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 决策模型解析")
class KnowledgeBaseQueryPropertiesTest {

    @Test
    @DisplayName("分项为空时回落到 decision-model")
    void fallsBackToDecisionModel() {
        KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
        properties.setDecisionModel("qwen3.5-flash");

        assertThat(properties.resolveDecisionModel(null)).isEqualTo("qwen3.5-flash");
        assertThat(properties.resolveDecisionModel("  ")).isEqualTo("qwen3.5-flash");
        assertThat(properties.resolveDecisionModel("qwen3.7-plus")).isEqualTo("qwen3.7-plus");
    }

    @Test
    @DisplayName("decision-model 为空时仍有 flash 兜底")
    void defaultsWhenDecisionModelBlank() {
        KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
        properties.setDecisionModel("");

        assertThat(properties.resolveDecisionModel("")).isEqualTo("qwen3.5-flash");
    }
}
