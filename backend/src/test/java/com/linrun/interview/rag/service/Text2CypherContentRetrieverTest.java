package com.linrun.interview.rag.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Text2Cypher 安全检索测试")
class Text2CypherContentRetrieverTest {

  @Test
  @DisplayName("用户图谱开启权限条件时拒绝无 ownerUserId 的查询并回退 ES")
  void rejectsUnscopedGraphQuery() {
    ChatModel chatModel = mock(ChatModel.class);
    Driver driver = mock(Driver.class);
    ContentRetriever fallback = query -> List.of(Content.from(TextSegment.from("ES fallback")));
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from("MATCH (n:Resume) RETURN n LIMIT 5"))
        .build());

    Text2CypherContentRetriever retriever = new Text2CypherContentRetriever(
        chatModel, driver, "neo4j", "{{question}}", "Resume(ownerUserId)", fallback,
        7L, 20, 1, true, "ownerUserId");

    assertThat(retriever.retrieve(Query.from("查询我的简历"))).extracting(c -> c.textSegment().text())
        .containsExactly("ES fallback");
    verify(driver, never()).session(any(SessionConfig.class));
  }

  @Test
  @DisplayName("选择知识库后拒绝未绑定知识库范围的图查询")
  void rejectsGraphQueryOutsideSelectedKnowledgeBases() {
    ChatModel chatModel = mock(ChatModel.class);
    Driver driver = mock(Driver.class);
    ContentRetriever fallback = query -> List.of(Content.from(TextSegment.from("ES fallback")));
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from(
            "MATCH (d:KnowledgeDocument)-[:HAS_VERSION]->(v:KnowledgeDocumentVersion) "
                + "WHERE d.ownerUserId = $dataUserId RETURN d LIMIT 5"))
        .build());

    Text2CypherContentRetriever retriever = new Text2CypherContentRetriever(
        chatModel, driver, "neo4j", "{{question}}", "KnowledgeDocument(docId,ownerUserId)", fallback,
        7L, 20, 1, true, "ownerUserId", List.of(11L, 12L));

    assertThat(retriever.retrieve(Query.from("查询当前资料的章节关系")))
        .extracting(c -> c.textSegment().text())
        .containsExactly("ES fallback");
    verify(driver, never()).session(any(SessionConfig.class));
  }

  @Test
  @DisplayName("领域图允许平台公开实体与当前用户实体的联合范围")
  @SuppressWarnings("unchecked")
  void acceptsPlatformAndCurrentUserScope() {
    ChatModel chatModel = mock(ChatModel.class);
    Driver driver = mock(Driver.class);
    Session session = mock(Session.class);
    Result result = mock(Result.class);
    ContentRetriever fallback = query -> List.of(Content.from(TextSegment.from("ES fallback")));
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from(
            "MATCH (a:KnowledgeEntity)-[r:RELATES_TO]->(b:KnowledgeEntity) "
                + "WHERE a.ownerUserId IN $graphOwnerIds "
                + "AND b.ownerUserId IN $graphOwnerIds "
                + "RETURN a.name AS source, r.relationType AS relation, b.name AS target LIMIT 5"))
        .build());
    when(driver.session(any(SessionConfig.class))).thenReturn(session);
    when(session.run(any(String.class), anyMap())).thenReturn(result);
    when(result.list(any())).thenReturn(List.of(java.util.Map.of(
        "source", "Agent", "relation", "USES", "target", "LangGraph")));

    Text2CypherContentRetriever retriever = new Text2CypherContentRetriever(
        chatModel, driver, "neo4j", "{{question}}", "KnowledgeEntity", fallback,
        7L, 20, 1, true, "ownerUserId", List.of(), 0L, false);

    assertThat(retriever.retrieve(Query.from("Agent 和 LangGraph 有什么关系")))
        .singleElement()
        .extracting(c -> c.textSegment().text())
        .asString()
        .contains("Agent", "LangGraph", "RELATES_TO");
    verify(session).run(contains("ownerUserId IN $graphOwnerIds"), anyMap());
  }
}
