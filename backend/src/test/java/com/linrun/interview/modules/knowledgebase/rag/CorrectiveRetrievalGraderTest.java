package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.ai.PromptTemplate;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CRAG 纠正式检索打分器测试")
class CorrectiveRetrievalGraderTest {

  private final PromptTemplate template = new PromptTemplate("{question} {documents}");
  private final List<Content> contents = List.of(Content.from(TextSegment.from("Redis 是内存数据库")));

  @Test
  @DisplayName("correct：片段可支撑回答")
  void gradeCorrect() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenReturn(
        "{\"grade\":\"correct\",\"reasoning\":\"直接相关\",\"correctedQuery\":\"\"}");
    CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(chatModel, template, 3, 400);

    CorrectiveRetrievalGrader.GradeResult result = grader.grade("Redis 是什么", contents);

    assertThat(result.grade()).isEqualTo(CorrectiveRetrievalGrader.Grade.CORRECT);
  }

  @Test
  @DisplayName("ambiguous：给出纠正查询用于重检索")
  void gradeAmbiguous() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenReturn(
        "{\"grade\":\"ambiguous\",\"reasoning\":\"部分相关\",\"correctedQuery\":\"Redis 持久化 RDB AOF\"}");
    CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(chatModel, template, 3, 400);

    CorrectiveRetrievalGrader.GradeResult result = grader.grade("Redis 怎么持久化", contents);

    assertThat(result.grade()).isEqualTo(CorrectiveRetrievalGrader.Grade.AMBIGUOUS);
    assertThat(result.correctedQuery()).isEqualTo("Redis 持久化 RDB AOF");
  }

  @Test
  @DisplayName("incorrect：片段全部无关")
  void gradeIncorrect() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenReturn(
        "{\"grade\":\"incorrect\",\"reasoning\":\"完全无关\",\"correctedQuery\":\"\"}");
    CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(chatModel, template, 3, 400);

    CorrectiveRetrievalGrader.GradeResult result = grader.grade("今天天气", contents);

    assertThat(result.grade()).isEqualTo(CorrectiveRetrievalGrader.Grade.INCORRECT);
  }

  @Test
  @DisplayName("打分失败按 correct 兜底，不阻断主链路")
  void gradeFailureFallsBackToCorrect() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(anyString())).thenThrow(new RuntimeException("timeout"));
    CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(chatModel, template, 3, 400);

    CorrectiveRetrievalGrader.GradeResult result = grader.grade("Redis 是什么", contents);

    assertThat(result.grade()).isEqualTo(CorrectiveRetrievalGrader.Grade.CORRECT);
  }
}
