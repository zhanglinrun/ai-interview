package com.linrun.interview.modules.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.report.dto.ReportContracts.SummaryContent;
import com.linrun.interview.modules.report.dto.ReportContracts.CapabilityGap;
import com.linrun.interview.modules.report.model.TrainingType;
import com.linrun.interview.modules.report.model.InterviewReportEntity;
import com.linrun.interview.modules.report.model.ReportStatus;
import com.linrun.interview.modules.report.service.ReportGenerationProcessor.ProcessOutcome;
import com.linrun.interview.modules.report.service.ReportPersistenceService.GenerationClaim;
import com.linrun.interview.modules.report.service.ReportPersistenceService.GenerationClaimState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("证据化复盘异步处理")
class ReportGenerationProcessorTest {

  private ReportPersistenceService persistence;
  private ReportFactAssembler assembler;
  private ReportGapSelector gapSelector;
  private ReportSummaryGenerator summaryGenerator;
  private ReportGenerationProcessor processor;

  @BeforeEach
  void setUp() {
    persistence = mock(ReportPersistenceService.class);
    assembler = mock(ReportFactAssembler.class);
    gapSelector = mock(ReportGapSelector.class);
    summaryGenerator = mock(ReportSummaryGenerator.class);
    processor = new ReportGenerationProcessor(
        persistence, assembler, gapSelector, summaryGenerator);
  }

  @Test
  @DisplayName("重复消息未抢到报告时不重复调用 BYOK")
  void shouldSkipWhenClaimLost() {
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.TERMINAL, null));

    ProcessOutcome outcome = processor.process("report-1", 7L);

    assertThat(outcome).isEqualTo(ProcessOutcome.FINISHED);
    verify(summaryGenerator, never()).generate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("claim 后进程宕机的重复消息在租约期内延后且不重复调用 BYOK")
  void shouldDeferWhenLeaseIsStillHeld() {
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.LEASE_HELD, report()));

    ProcessOutcome outcome = processor.process("report-1", 7L);

    assertThat(outcome).isEqualTo(ProcessOutcome.DEFERRED);
    verify(summaryGenerator, never()).generate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("总结成功后原子提交报告、能力证据与训练缺口")
  void shouldCompleteReport() {
    InterviewReportEntity report = report();
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L).userId(7L).sessionId("session-1").build();
    SummaryContent summary = new SummaryContent("完成", List.of("强项"), List.of("改进"));
    var assembly = new ReportFactAssembler.Assembly(List.of(), List.of());
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.ACQUIRED, report));
    when(persistence.requireSessionForReport(report)).thenReturn(session);
    when(persistence.readFacts(report)).thenReturn(List.of());
    when(summaryGenerator.generate(7L, "session-1", "report-1", List.of()))
        .thenReturn(summary);
    when(assembler.assemble(session, "report-1")).thenReturn(assembly);
    when(gapSelector.select(List.of())).thenReturn(List.of());

    processor.process("report-1", 7L);

    verify(persistence).complete(
        "report-1", 7L,
        new SummaryContent("完成", List.of("强项"), List.of()), List.of(), List.of());
  }

  @Test
  @DisplayName("总结改进项与实际生成的训练缺口保持一致")
  void shouldAlignSummaryImprovementsWithGaps() {
    InterviewReportEntity report = report();
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L).userId(7L).sessionId("session-1").build();
    SummaryContent summary = new SummaryContent("完成", List.of("强项"), List.of("模型建议"));
    CapabilityGap gap = new CapabilityGap(
        "MYSQL_TX", "事务一致性", "事务边界需要补强", 11L,
        List.of("evidence-1"), TrainingType.ENGINEERING_SCENARIO, null);
    var assembly = new ReportFactAssembler.Assembly(List.of(), List.of());
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.ACQUIRED, report));
    when(persistence.requireSessionForReport(report)).thenReturn(session);
    when(persistence.readFacts(report)).thenReturn(List.of());
    when(summaryGenerator.generate(7L, "session-1", "report-1", List.of()))
        .thenReturn(summary);
    when(assembler.assemble(session, "report-1")).thenReturn(assembly);
    when(gapSelector.select(List.of())).thenReturn(List.of(gap));

    processor.process("report-1", 7L);

    verify(persistence).complete(
        eq("report-1"), eq(7L),
        eq(new SummaryContent("完成", List.of("强项"),
            List.of("事务一致性：事务边界需要补强"))),
        eq(List.of(gap)), eq(List.of()));
  }

  @Test
  @DisplayName("BYOK 或结构化输出失败时保留客观事实并标记可重试失败")
  void shouldMarkFailedWithoutHidingFacts() {
    InterviewReportEntity report = report();
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L).userId(7L).sessionId("session-1").build();
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.ACQUIRED, report));
    when(persistence.requireSessionForReport(report)).thenReturn(session);
    when(persistence.readFacts(report)).thenReturn(List.of());
    when(summaryGenerator.generate(eq(7L), eq("session-1"), eq("report-1"), any()))
        .thenThrow(new BusinessException(ErrorCode.USER_LLM_NOT_CONFIGURED));

    processor.process("report-1", 7L);

    verify(persistence).markFailed(
        eq("report-1"), eq(7L), eq("REPORT_GENERATION_FAILED"), any());
    verify(persistence, never()).complete(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("未知异常时释放生成租约避免报告永久停在生成中")
  void shouldReleaseClaimWhenUnexpectedFailureOccurs() {
    InterviewReportEntity report = report();
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L).userId(7L).sessionId("session-1").build();
    when(persistence.claim("report-1", 7L)).thenReturn(
        new GenerationClaim(GenerationClaimState.ACQUIRED, report));
    when(persistence.requireSessionForReport(report)).thenReturn(session);
    when(persistence.readFacts(report)).thenReturn(List.of());
    when(summaryGenerator.generate(eq(7L), eq("session-1"), eq("report-1"), any()))
        .thenThrow(new IllegalStateException("provider response must not be exposed"));

    processor.process("report-1", 7L);

    verify(persistence).markFailed(
        "report-1", 7L, "REPORT_PIPELINE_FAILED", "复盘生成异常，请稍后手动重试");
    verify(persistence, never()).complete(any(), any(), any(), any(), any());
  }

  private InterviewReportEntity report() {
    return InterviewReportEntity.builder()
        .reportId("report-1")
        .userId(7L)
        .sessionId(9L)
        .status(ReportStatus.GENERATING)
        .build();
  }
}
