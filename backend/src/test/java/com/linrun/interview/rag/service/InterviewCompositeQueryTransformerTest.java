package com.linrun.interview.rag.service;

import com.linrun.interview.ai.service.PromptTemplate;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("改写 + HyDE 组合器")
class InterviewCompositeQueryTransformerTest {

    @Test
    @DisplayName("HyDE 超时后只保留改写 query")
    void dropsHydeWhenTimedOut() {
        ChatModel rewriteModel = mock(ChatModel.class);
        InterviewQueryTransformer rewrite = new InterviewQueryTransformer(
            rewriteModel, new PromptTemplate("{question}"), false);
        ChatModel hydeModel = mock(ChatModel.class);
        when(hydeModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(200);
            throw new IllegalStateException("should have timed out");
        });

        InterviewCompositeQueryTransformer transformer = new InterviewCompositeQueryTransformer(
            rewrite, hydeModel, new PromptTemplate("{question} {maxChars}"), true, 80, 50);

        List<Query> queries = transformer.transform(Query.from("Redis 为什么快"));

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().text()).contains("Redis");
    }
}
