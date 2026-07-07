package com.linrun.interview.modules.knowledgebase.model;

import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一评测运行响应。
 */
public record EvalRunResponse(
    String runId,
    String title,
    String baselineKey,
    boolean baseline,
    double overallScore,
    boolean regression,
    IntentEvaluationResult intent,
    RagEvalResponse rag,
    JudgeEvaluationResult judge,
    BaselineComparison baselineComparison,
    LocalDateTime createdAt
) {

  public record IntentEvaluationResult(
      int total,
      int correct,
      double accuracy,
      double macroF1,
      List<IntentItemResult> items
  ) {
  }

  public record IntentItemResult(
      String question,
      String expectedIntent,
      Boolean expectedRelated,
      String actualIntent,
      boolean actualRelated,
      double confidence,
      boolean correct,
      String reason,
      List<IntentRecognitionResult.StrategyScore> strategies
  ) {
  }

  public record JudgeEvaluationResult(
      int total,
      int passed,
      double passRate,
      double averageOverall,
      double averageRelevance,
      double averageAccuracy,
      double averageCompleteness,
      double averageHelpfulness,
      List<JudgeItemResult> items
  ) {
  }

  public record JudgeItemResult(
      String question,
      double minOverallScore,
      boolean passed,
      double relevance,
      double accuracy,
      double completeness,
      double helpfulness,
      double overall,
      String reason,
      String improvement
  ) {
  }

  public record BaselineComparison(
      String baselineRunId,
      LocalDateTime baselineCreatedAt,
      double threshold,
      List<MetricDelta> metrics
  ) {
  }

  public record MetricDelta(
      String metric,
      double current,
      double baseline,
      double delta,
      boolean regressed
  ) {
  }
}
