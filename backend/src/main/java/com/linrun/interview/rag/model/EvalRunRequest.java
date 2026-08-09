package com.linrun.interview.rag.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 统一评测运行请求。
 *
 * <p>一次运行可以同时覆盖面试场景意图识别和 RAG 检索评测。调用方可通过
 * {@code updateBaseline} 把本次结果标记为新的对照基线，后续运行会与同一
 * {@code baselineKey} 下最近一次基线做退化判断。
 */
public record EvalRunRequest(
    String title,
    String baselineKey,
    Boolean updateBaseline,
    Double regressionThreshold,
    @Valid List<IntentCase> intentCases,
    @Valid RagEvalRequest rag,
    @Valid List<JudgeCase> judgeCases
) {

  public EvalRunRequest(
      String title,
      String baselineKey,
      Boolean updateBaseline,
      Double regressionThreshold,
      List<IntentCase> intentCases,
      RagEvalRequest rag
  ) {
    this(title, baselineKey, updateBaseline, regressionThreshold, intentCases, rag, null);
  }

  public record IntentCase(
      @NotBlank String question,
      String expectedIntent,
      Boolean expectedRelated
  ) {
  }

  public record JudgeCase(
      @NotBlank String question,
      @NotBlank String answer,
      String referenceAnswer,
      String context,
      Double minOverallScore
  ) {
  }
}
