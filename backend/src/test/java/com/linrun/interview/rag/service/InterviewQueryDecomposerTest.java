package com.linrun.interview.rag.service;

import com.linrun.interview.ai.service.PromptTemplate;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("查询分解器（Agentic RAG）测试")
class InterviewQueryDecomposerTest {

  private final QueryTransformer delegate = query -> List.of(Query.from(query.text()));
  private final PromptTemplate template = new PromptTemplate("{question} {maxSubQueries}");

  @Nested
  @DisplayName("规则预筛")
  class RulePrefilter {

    @Test
    @DisplayName("简单问题不触发 LLM 分解，只返回改写链结果")
    void simpleQuestionSkipsLlm() {
      ChatModel chatModel = mock(ChatModel.class);
      InterviewQueryDecomposer decomposer =
          new InterviewQueryDecomposer(delegate, chatModel, template, 4, null, null);

      List<Query> result = decomposer.transform(Query.from("什么是 JVM"));

      assertThat(result).hasSize(1);
      verify(chatModel, never()).chat(anyString());
    }

    @Test
    @DisplayName("过短问题直接跳过")
    void shortQuestionSkips() {
      assertThat(InterviewQueryDecomposer.isLikelyComplex("对比")).isFalse();
      assertThat(InterviewQueryDecomposer.isLikelyComplex("Redis 和 MySQL 的区别")).isTrue();
    }
  }

  @Nested
  @DisplayName("LLM 分解")
  class LlmDecompose {

    @Test
    @DisplayName("复杂问题分解成功时原 query 与子查询一并返回")
    void complexQuestionDecomposed() {
      ChatModel chatModel = mock(ChatModel.class);
      when(chatModel.chat(anyString())).thenReturn(
          "{\"complex\":true,\"reasoning\":\"对比类\","
              + "\"subQueries\":[\"Redis 持久化机制\",\"MySQL 持久化机制\"]}");
      InterviewQueryDecomposer decomposer =
          new InterviewQueryDecomposer(delegate, chatModel, template, 4, null, null);

      List<Query> result = decomposer.transform(Query.from("Redis 和 MySQL 的持久化区别"));

      assertThat(result).hasSizeGreaterThanOrEqualTo(3);
      assertThat(result).extracting(Query::text)
          .contains("Redis 持久化机制", "MySQL 持久化机制");
    }

    @Test
    @DisplayName("LLM 判定非复杂时不追加子查询")
    void llmSaysNotComplex() {
      ChatModel chatModel = mock(ChatModel.class);
      when(chatModel.chat(anyString())).thenReturn("{\"complex\":false,\"subQueries\":[]}");
      InterviewQueryDecomposer decomposer =
          new InterviewQueryDecomposer(delegate, chatModel, template, 4, null, null);

      List<Query> result = decomposer.transform(Query.from("Redis 和 MySQL 的区别"));

      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("LLM 调用失败时降级返回原改写链，不抛异常")
    void llmFailureDegrades() {
      ChatModel chatModel = mock(ChatModel.class);
      when(chatModel.chat(anyString())).thenThrow(new RuntimeException("timeout"));
      InterviewQueryDecomposer decomposer =
          new InterviewQueryDecomposer(delegate, chatModel, template, 4, null, null);

      List<Query> result = decomposer.transform(Query.from("Redis 和 MySQL 的区别"));

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().text()).isEqualTo("Redis 和 MySQL 的区别");
    }
  }
}
