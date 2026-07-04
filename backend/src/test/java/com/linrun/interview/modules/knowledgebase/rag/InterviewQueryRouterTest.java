package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("面试查询路由器测试")
class InterviewQueryRouterTest {

    private final ContentRetriever vectorRetriever = query -> List.of();
    private final ContentRetriever fullTextRetriever = query -> List.of();
    private final ContentRetriever sqlRetriever = query -> List.of();

  private InterviewQueryRouter router(List<ContentRetriever> esRetrievers,
                                      ContentRetriever sql,
                                      ContentRetriever neo4j,
                                      ChatModel chatModel,
                                      boolean enabled,
                                      InterviewIntent intentHint) {
    return InterviewQueryRouter.builder()
        .elasticsearchRetrievers(esRetrievers)
        .sqlRetriever(sql)
        .neo4jRetriever(neo4j)
        .chatModel(chatModel)
        .enabled(enabled)
        .intentHint(intentHint)
        .build();
  }

  @Test
  @DisplayName("结构化统计问题应直接路由到 SQL")
  void ruleRoutesToSql() {
    InterviewQueryRouter router = router(
        List.of(vectorRetriever, fullTextRetriever), sqlRetriever, null, mock(ChatModel.class), true, null);

    assertThat(router.route(new Query("我最近几次面试平均分是多少")))
        .containsExactly(sqlRetriever);
  }

  @Test
  @DisplayName("图结构问题应路由到 Neo4j")
  void ruleRoutesToGraph() {
    ContentRetriever neo4jRetriever = query -> List.of();
    InterviewQueryRouter router = router(
        List.of(vectorRetriever), sqlRetriever, neo4jRetriever, mock(ChatModel.class), true, null);

    assertThat(router.route(new Query("Java 和 Spring 的知识点依赖链是什么")))
        .containsExactly(neo4jRetriever);
  }

  @Test
  @DisplayName("knowledge_base 路由应返回双通道 ES retriever")
  void knowledgeBaseRoutesDualEs() {
    InterviewQueryRouter router = router(
        List.of(vectorRetriever, fullTextRetriever), sqlRetriever, null, null, false, null);

    assertThat(router.route(new Query("解释 Spring IOC")))
        .containsExactly(vectorRetriever, fullTextRetriever);
  }

  @Test
  @DisplayName("LLM 返回 hybrid 时应同时路由 SQL 和 ES")
  void llmRoutesToHybrid() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("{\"strategy\":\"hybrid\",\"confidence\":0.9}");
    InterviewQueryRouter router = router(
        List.of(vectorRetriever), sqlRetriever, null, chatModel, true, null);

    assertThat(router.route(new Query("结合我的薄弱项解释 JVM GC")))
        .containsExactly(sqlRetriever, vectorRetriever);
  }

  @Test
  @DisplayName("路由关闭时应只走 ES")
  void disabledRoutesToEs() {
    InterviewQueryRouter router = router(
        List.of(vectorRetriever, fullTextRetriever), sqlRetriever, null, null, false, null);

    assertThat(router.route(new Query("平均分是多少")))
        .containsExactly(vectorRetriever, fullTextRetriever);
  }

  @Test
  @DisplayName("LLM 路由失败时应降级 hybrid 多路检索")
  void llmFailureRoutesToHybrid() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenThrow(new RuntimeException("bad json"));
    InterviewQueryRouter router = router(
        List.of(vectorRetriever), sqlRetriever, null, chatModel, true, null);

    assertThat(router.route(new Query("结合历史分数解释 JVM")))
        .containsExactly(sqlRetriever, vectorRetriever);
  }

  @Test
  @DisplayName("RESUME_STATS 意图应路由到 SQL")
  void intentRoutesResumeStatsToSql() {
    InterviewQueryRouter router = router(
        List.of(vectorRetriever), sqlRetriever, null, mock(ChatModel.class), true,
        InterviewIntent.RESUME_STATS);

    assertThat(router.route(new Query("帮我看看")))
        .containsExactly(sqlRetriever);
  }

  @Test
  @DisplayName("DATA_QUERY 意图应路由到 SQL")
  void intentRoutesDataQueryToSql() {
    InterviewQueryRouter router = router(
        List.of(vectorRetriever), sqlRetriever, null, mock(ChatModel.class), true,
        InterviewIntent.DATA_QUERY);

    assertThat(router.route(new Query("统计一下")))
        .containsExactly(sqlRetriever);
  }
}
