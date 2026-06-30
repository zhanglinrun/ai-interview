package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("面试查询路由器测试")
class InterviewQueryRouterTest {

    private final ContentRetriever esRetriever = query -> List.of();
    private final ContentRetriever sqlRetriever = query -> List.of();

    @Test
    @DisplayName("结构化统计问题应直接路由到 SQL")
    void ruleRoutesToSql() {
        InterviewQueryRouter router = new InterviewQueryRouter(
            esRetriever, sqlRetriever, mock(ChatModel.class), true, null);

        assertThat(router.route(new Query("我最近几次面试平均分是多少")))
            .containsExactly(sqlRetriever);
    }

    @Test
    @DisplayName("LLM 返回 hybrid 时应同时路由 SQL 和 ES")
    void llmRoutesToHybrid() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"strategy\":\"hybrid\",\"confidence\":0.9}");
        InterviewQueryRouter router = new InterviewQueryRouter(
            esRetriever, sqlRetriever, chatModel, true, null);

        assertThat(router.route(new Query("结合我的薄弱项解释 JVM GC")))
            .containsExactly(sqlRetriever, esRetriever);
    }

    @Test
    @DisplayName("路由关闭时应只走 ES")
    void disabledRoutesToEs() {
        InterviewQueryRouter router = new InterviewQueryRouter(
            esRetriever, sqlRetriever, mock(ChatModel.class), false, null);

        assertThat(router.route(new Query("平均分是多少")))
            .containsExactly(esRetriever);
    }
}
