package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Text2SQL 安全检索测试")
class Text2SqlContentRetrieverTest {

  @Test
  @DisplayName("生成安全 SQL 后执行并标记关系库来源")
  void executesScopedSelect() {
    ChatModel chatModel = mock(ChatModel.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(
        "SELECT id, overall_score FROM interview_sessions WHERE user_id = 7 ORDER BY id DESC"));
    when(jdbcTemplate.queryForList(any(String.class)))
        .thenReturn(List.of(Map.of("id", 11L, "overall_score", 92)));

    Text2SqlContentRetriever retriever = new Text2SqlContentRetriever(
        chatModel, jdbcTemplate, "{{question}}", "interview_sessions(user_id, overall_score)",
        Set.of("interview_sessions"), null, 7L, 20);

    List<Content> contents = retriever.retrieve(Query.from("我最近一次面试多少分"));

    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst().textSegment().text()).contains("overall_score=92");
    assertThat(contents.getFirst().textSegment().metadata()
        .getString(MetadataKeyConstant.RETRIEVAL_SOURCE)).isEqualTo("RELATIONAL_DB");
    verify(jdbcTemplate).queryForList(
        "SELECT id, overall_score FROM interview_sessions WHERE user_id = 7 ORDER BY id DESC LIMIT 20");
  }

  @Test
  @DisplayName("未授权表或跨用户条件回退 ES")
  void rejectsUnsafeSqlAndFallsBack() {
    ChatModel chatModel = mock(ChatModel.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ContentRetriever fallback = query -> List.of(Content.from(TextSegment.from("ES fallback")));
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(
        "SELECT * FROM interview_sessions WHERE user_id = 8"));

    Text2SqlContentRetriever retriever = new Text2SqlContentRetriever(
        chatModel, jdbcTemplate, "{{question}}", "", Set.of("interview_sessions"), fallback, 7L, 20);

    assertThat(retriever.retrieve(Query.from("查询别人的面试记录"))).extracting(c -> c.textSegment().text())
        .containsExactly("ES fallback");
  }

  private ChatResponse response(String text) {
    return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
  }
}
