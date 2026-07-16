package com.linrun.interview.modules.report.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.modules.report.dto.ReportContracts.CapabilityGap;
import com.linrun.interview.modules.report.dto.ReportContracts.SummaryContent;
import com.linrun.interview.modules.report.model.InterviewReportEntity;
import com.linrun.interview.modules.report.service.ReportPersistenceService.GenerationClaimState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationProcessor {

  private final ReportPersistenceService persistenceService;
  private final ReportFactAssembler factAssembler;
  private final ReportGapSelector gapSelector;
  private final ReportSummaryGenerator summaryGenerator;

  public ProcessOutcome process(String reportId, Long userId) {
    var claim = persistenceService.claim(reportId, userId);
    if (claim.state() == GenerationClaimState.LEASE_HELD) {
      return ProcessOutcome.DEFERRED;
    }
    InterviewReportEntity report = claim.report();
    if (claim.state() != GenerationClaimState.ACQUIRED || report == null) {
      return ProcessOutcome.FINISHED;
    }
    try {
      var session = persistenceService.requireSessionForReport(report);
      var facts = persistenceService.readFacts(report);
      var summary = summaryGenerator.generate(
          userId, session.getSessionId(), reportId, facts);
      var assembly = factAssembler.assemble(session, reportId);
      var gaps = gapSelector.select(assembly.capabilityEvidence());
      var alignedSummary = alignImprovements(summary, gaps);
      persistenceService.complete(
          reportId, userId, alignedSummary, gaps, assembly.capabilityEvidence());
    } catch (BusinessException e) {
      // BYOK、结构化输出等业务失败保留客观事实并允许用户手动重试。
      persistenceService.markFailed(
          reportId, userId, "REPORT_GENERATION_FAILED", e.getMessage());
    } catch (RuntimeException e) {
      // 未知异常也必须释放 generation claim，否则 broker 的立即重投会因 10 分钟租约
      // 抢占失败而被当作成功跳过，报告会永久停在 GENERATING。
      log.error(
          "证据化复盘生成异常，已标记为可重试失败: reportId={}, userId={}, failureType={}",
          reportId, userId, e.getClass().getSimpleName());
      persistenceService.markFailed(
          reportId, userId, "REPORT_PIPELINE_FAILED", "复盘生成异常，请稍后手动重试");
    }
    return ProcessOutcome.FINISHED;
  }

  private SummaryContent alignImprovements(
      SummaryContent summary,
      List<CapabilityGap> gaps
  ) {
    if (summary == null) {
      return summary;
    }
    List<String> improvements = gaps == null ? List.of() : gaps.stream()
        .map(gap -> gap.capabilityName() + "：" + gap.reason())
        .toList();
    return new SummaryContent(
        summary.overallFeedback(), summary.strengths(), improvements);
  }

  public enum ProcessOutcome {
    FINISHED,
    DEFERRED
  }
}
