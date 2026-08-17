package com.linrun.interview.rag.service;

import com.linrun.interview.rag.model.RagQueryTrace;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import dev.langchain4j.model.chat.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 多数据源路由测试")
class MultiSourceQueryRouterTest {

  @Test
  @DisplayName("关系类问题路由到 MySQL")
  void routesRelationalQuestion() {
    Map<MultiSourceQueryRouter.Source, ContentRetriever> retrievers = retrievers();
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(retrievers, null, false, null, null);

    MultiSourceQueryRouter.Decision decision = router.decide("统计我最近三次面试的平均分");

    assertThat(decision.source()).isEqualTo(MultiSourceQueryRouter.Source.RELATIONAL_DB);
    assertThat(decision.confidence()).isGreaterThan(0.8);
  }

  @Test
  @DisplayName("依赖/调用链问题路由到 Neo4j")
  void routesGraphQuestion() {
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(retrievers(), null, false, null, null);

    MultiSourceQueryRouter.Decision decision = router.decide("查询订单服务到支付服务的调用链和上下游依赖");

    assertThat(decision.source()).isEqualTo(MultiSourceQueryRouter.Source.GRAPH_DB);
  }

  @Test
  @DisplayName("平台技术实体关系问题路由到 Neo4j")
  void routesDomainEntityRelationQuestion() {
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(retrievers(), null, false, null, null);

    MultiSourceQueryRouter.Decision decision = router.decide("Agent 和 LangGraph 怎么配合");

    assertThat(decision.source()).isEqualTo(MultiSourceQueryRouter.Source.GRAPH_DB);
  }

  @Test
  @DisplayName("八股里的数据库字样不应进 Text2SQL")
  void keepsInterviewDatabaseQuestionOnKnowledgeBase() {
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(retrievers(), null, false, null, null);

    MultiSourceQueryRouter.Decision decision = router.decide("如何保证缓存和数据库的数据一致性？");

    assertThat(decision.source()).isEqualTo(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE);
  }

  @Test
  @DisplayName("父子/兄弟分段问题保留知识库上下文扩展")
  void routesChunkStructureToKnowledgeBase() {
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(retrievers(), null, false, null, null);

    MultiSourceQueryRouter.Decision decision = router.decide("这个文档的父子分段和兄弟分段怎么扩展");

    assertThat(decision.source()).isEqualTo(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE);
  }

  @Test
  @DisplayName("未配置目标源时回退知识库")
  void fallsBackWhenTargetMissing() {
    ContentRetriever es = mock(ContentRetriever.class);
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(
        Map.of(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE, es), null, false, null, null);

    assertThat(router.route(Query.from("统计我的面试记录"))).containsExactly(es);
  }

  @Test
  @DisplayName("八股问题不需要组装 SQL/图检索器")
  void knowledgeBaseHeuristicSkipsStructuredSources() {
    assertThat(MultiSourceQueryRouter.needsStructuredSource("reids为什么这么快")).isFalse();
    assertThat(MultiSourceQueryRouter.needsStructuredSource("统计我最近三次面试的平均分")).isTrue();
  }

  @Test
  @DisplayName("默认知识库启发式不调用路由 LLM")
  void skipsRouteLlmWhenHeuristicIsKnowledgeBase() {
    ChatModel chatModel = mock(ChatModel.class);
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(
        retrievers(), chatModel, true, null, null);

    assertThat(router.route(Query.from("RocketMQ 是什么"))).hasSize(1);
    verify(chatModel, never()).chat(anyString());
  }

  @Test
  @DisplayName("同一请求内多次 route 只决策一次")
  void reusesFirstRouteDecision() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenReturn(
        "{\"intent\":\"统计\",\"strategy\":\"relational_db\",\"confidence\":0.9,\"reasoning\":\"命中统计\"}");
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(
        retrievers(), chatModel, true, null, null);

    assertThat(router.route(Query.from("统计我最近三次面试的平均分"))).hasSize(1);
    assertThat(router.route(Query.from("HyDE 假设文档：候选人三次面试得分汇总"))).hasSize(1);
    verify(chatModel, times(1)).chat(anyString());
  }

  @Test
  @DisplayName("传入 trace 时应写入 ROUTE span")
  void recordsRouteSpan() {
    ContentRetriever es = mock(ContentRetriever.class);
    RagQueryTrace trace = new RagQueryTrace();
    MultiSourceQueryRouter router = new MultiSourceQueryRouter(
        Map.of(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE, es), null, false, null, trace);

    assertThat(router.route(Query.from("RocketMQ 是什么"))).containsExactly(es);
    assertThat(trace.spans()).hasSize(1);
    RagQueryTrace.Span span = trace.spans().getFirst();
    assertThat(span.name()).isEqualTo(RagQueryTrace.SPAN_ROUTE);
    assertThat(span.closed()).isTrue();
    assertThat(span.dataSource()).isEqualTo("knowledge_base");
    assertThat(span.output()).contains("knowledge_base");
  }

  private Map<MultiSourceQueryRouter.Source, ContentRetriever> retrievers() {
    EnumMap<MultiSourceQueryRouter.Source, ContentRetriever> result =
        new EnumMap<>(MultiSourceQueryRouter.Source.class);
    result.put(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE, mock(ContentRetriever.class));
    result.put(MultiSourceQueryRouter.Source.RELATIONAL_DB, mock(ContentRetriever.class));
    result.put(MultiSourceQueryRouter.Source.GRAPH_DB, mock(ContentRetriever.class));
    return result;
  }
}
