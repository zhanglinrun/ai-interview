package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

  private Map<MultiSourceQueryRouter.Source, ContentRetriever> retrievers() {
    EnumMap<MultiSourceQueryRouter.Source, ContentRetriever> result =
        new EnumMap<>(MultiSourceQueryRouter.Source.class);
    result.put(MultiSourceQueryRouter.Source.KNOWLEDGE_BASE, mock(ContentRetriever.class));
    result.put(MultiSourceQueryRouter.Source.RELATIONAL_DB, mock(ContentRetriever.class));
    result.put(MultiSourceQueryRouter.Source.GRAPH_DB, mock(ContentRetriever.class));
    return result;
  }
}
