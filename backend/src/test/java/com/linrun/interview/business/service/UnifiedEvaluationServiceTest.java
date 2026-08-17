package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linrun.interview.ai.service.StructuredOutputInvoker;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

@DisplayName("统一评估服务失败语义")
class UnifiedEvaluationServiceTest {

  private StructuredOutputInvoker invoker;
  private UnifiedEvaluationService service;
  private ChatModel chatModel;

  @BeforeEach
  void setUp() throws Exception {
    invoker = mock(StructuredOutputInvoker.class);
    chatModel = mock(ChatModel.class);
    InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
    properties.setBatchSize(3);
    service = new UnifiedEvaluationService(invoker, new DefaultResourceLoader(), properties);
  }

  @Test
  @DisplayName("全部批次失败时上抛，不落 0 分成功报告")
  void throwsWhenEveryBatchFails() {
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "parse failed"));

    assertThatThrownBy(() -> service.evaluate(chatModel, "s1", List.of(
        new QaRecord(0, "Q1", "Java", "很长的回答一"),
        new QaRecord(1, "Q2", "Java", "很长的回答二")
    ), ""))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("全部评估批次失败");
  }

  @Test
  @DisplayName("部分批次成功时保留真分，失败题未评")
  void keepsScoresForSuccessfulBatch() {
    UnifiedEvaluationService.BatchReportDTO first = new UnifiedEvaluationService.BatchReportDTO(
        80, "第一批尚可", List.of("清楚"), List.of("补例子"),
        List.of(
            new UnifiedEvaluationService.QuestionEvalDTO(0, 80, "不错", "", List.of()),
            new UnifiedEvaluationService.QuestionEvalDTO(1, 70, "一般", "", List.of()),
            new UnifiedEvaluationService.QuestionEvalDTO(2, 90, "很好", "", List.of())
        ));
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(first)
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "batch 2 failed"))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "summary skipped"));

    EvaluationReport report = service.evaluate(chatModel, "s1", List.of(
        new QaRecord(0, "Q1", "Java", "答1"),
        new QaRecord(1, "Q2", "Java", "答2"),
        new QaRecord(2, "Q3", "Java", "答3"),
        new QaRecord(3, "Q4", "Redis", "答4")
    ), "");

    assertThat(report.questionDetails()).hasSize(4);
    assertThat(report.questionDetails().get(0).score()).isEqualTo(80);
    assertThat(report.questionDetails().get(3).score()).isNull();
    assertThat(report.questionDetails().get(3).feedback()).contains("未成功生成评估结果");
    assertThat(report.overallScore()).isEqualTo(80);
    assertThat(report.overallFeedback()).contains("已评 3/4 题");
  }
}
