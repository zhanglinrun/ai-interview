package com.linrun.interview.modules.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.StructuredOutputInvoker;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.report.dto.ReportContracts.ObjectiveFact;
import com.linrun.interview.modules.report.dto.ReportContracts.SummaryContent;
import dev.langchain4j.model.chat.ChatModel;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

@DisplayName("岗位实战复盘总结生成")
class ReportSummaryGeneratorTest {

  private LlmProviderRegistry registry;
  private StructuredOutputInvoker invoker;
  private ReportSummaryGenerator generator;

  @BeforeEach
  void setUp() {
    registry = mock(LlmProviderRegistry.class);
    invoker = mock(StructuredOutputInvoker.class);
    generator = new ReportSummaryGenerator(registry, invoker, new ObjectMapper());
  }

  @Test
  @DisplayName("未作答事实使用用户语言传给模型且清理模型内部表达")
  void shouldUseUserFacingLanguageForUnansweredFacts() {
    ChatModel chatModel = mock(ChatModel.class);
    when(registry.getUserChatModel(7L)).thenReturn(chatModel);
    when(invoker.invoke(
        eq(chatModel), anyString(), anyString(), any(Type.class),
        eq(ErrorCode.INTERVIEW_EVALUATION_FAILED), anyString(), anyString(), any(Logger.class)))
        .thenReturn(new SummaryContent(
            "候选人未提供有效回答（answer为null）",
            List.of("assessmentStatus 为 COMPLETED"),
            List.of("下一题 answer=null")));
    ObjectiveFact unanswered = new ObjectiveFact(
        11L, 1, "POSITION_TECH", "解释事务传播", null, "PENDING",
        null, null, null, EvidenceStatus.NONE, null,
        null, null, null, null, null, null, null, List.of(), true);

    SummaryContent result = generator.generate(7L, "session-1", "report-1", List.of(unanswered));

    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
    verify(invoker).invoke(
        eq(chatModel), anyString(), promptCaptor.capture(), any(Type.class),
        eq(ErrorCode.INTERVIEW_EVALUATION_FAILED), anyString(), anyString(), any(Logger.class));
    assertThat(promptCaptor.getValue())
        .contains("\"作答情况\":\"未作答\"")
        .contains("\"技术正确性\":\"待评估\"")
        .doesNotContain(":null")
        .doesNotContain("\"answer\"");
    assertThat(result.overallFeedback()).isEqualTo("后续题目未作答");
    assertThat(result.strengths()).containsExactly("评估状态 为 COMPLETED");
    assertThat(result.improvements()).containsExactly("下一题 未作答");
  }
}
