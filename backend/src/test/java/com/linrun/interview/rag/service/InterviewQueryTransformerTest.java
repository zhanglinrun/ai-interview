package com.linrun.interview.rag.service;

import com.linrun.interview.ai.service.PromptTemplate;
import com.linrun.interview.rag.model.RagQueryTrace;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("查询改写器测试")
class InterviewQueryTransformerTest {

  @Test
  @DisplayName("规则预处理在 LLM 失败时仍应生效")
  void ruleFallbackWhenLlmFails() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("timeout"));
    PromptTemplate template = new PromptTemplate("rewrite {question} {history}");
    InterviewQueryTransformer transformer =
        new InterviewQueryTransformer(chatModel, template, true);

    List<Query> result = transformer.transform(Query.from("sping boot 面试题"));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().text()).contains("Spring Boot");
  }

  @Test
  @DisplayName("关闭 LLM 改写时规则仍应生效")
  void rulesApplyWhenLlmRewriteDisabled() {
    ChatModel chatModel = mock(ChatModel.class);
    PromptTemplate template = new PromptTemplate("rewrite {question} {history}");
    InterviewQueryTransformer transformer =
        new InterviewQueryTransformer(chatModel, template, false);

    List<Query> result = transformer.transform(Query.from("重栽和重写有啥区别啊"));

    assertThat(result.getFirst().text()).contains("重载");
  }

  @Test
  @DisplayName("传入 trace 时应写入 REWRITE span")
  void recordsRewriteSpan() {
    PromptTemplate template = new PromptTemplate("rewrite {question} {history}");
    RagQueryTrace trace = new RagQueryTrace();
    InterviewQueryTransformer transformer = new InterviewQueryTransformer(
        mock(ChatModel.class), template, false, null, null, null, trace);

    transformer.transform(Query.from("重栽和重写有啥区别啊"));

    assertThat(trace.spans()).hasSize(1);
    RagQueryTrace.Span span = trace.spans().getFirst();
    assertThat(span.name()).isEqualTo(RagQueryTrace.SPAN_REWRITE);
    assertThat(span.closed()).isTrue();
    assertThat(span.status()).isEqualTo("COMPLETED");
    assertThat(span.output()).contains("重载");
    assertThat(span.latencyMs()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("LLM 改写成功时应返回改写 query")
  void llmRewriteSuccess() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(dev.langchain4j.data.message.AiMessage.from("Spring Boot 核心原理"))
        .build());
    PromptTemplate template = new PromptTemplate("rewrite {question} {history}");
    InterviewQueryTransformer transformer =
        new InterviewQueryTransformer(chatModel, template, true);

    List<Query> result = transformer.transform(Query.from("sping boot"));

    assertThat(result.getFirst().text()).isEqualTo("Spring Boot 核心原理");
  }
}
