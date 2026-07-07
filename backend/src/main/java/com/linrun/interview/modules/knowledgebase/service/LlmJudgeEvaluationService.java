package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.StructuredOutputInvoker;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.knowledgebase.model.EvalRunRequest;
import com.linrun.interview.modules.knowledgebase.model.EvalRunResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-as-Judge 回答质量评测服务。
 *
 * <p>该服务只在统一评测运行中使用，不进入线上 RAG 问答主链路，避免正常用户请求额外等待
 * 一次裁判模型调用。单条裁判失败会转换成未通过的评测项，不中断整次评测运行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmJudgeEvaluationService {

  private static final double DEFAULT_MIN_OVERALL_SCORE = 0.75;
  private static final int MAX_CONTEXT_CHARS = 4000;
  private static final int MAX_ANSWER_CHARS = 4000;
  private static final int MAX_REFERENCE_CHARS = 2000;

  private static final String SYSTEM_PROMPT = """
      # Role
      你是一位严格但公允的 AI 面试平台回答质量裁判。

      # Task
      请评估候选回答是否真正回答了用户问题。你需要从四个维度分别给 0.0 到 1.0 的分数：
      - relevance：回答是否紧扣问题。
      - accuracy：回答是否准确，是否与参考答案或上下文冲突。
      - completeness：回答是否覆盖关键要点。
      - helpfulness：回答是否对用户下一步行动有帮助。

      # Rules
      1. 如果提供了参考答案，以参考答案为主要依据。
      2. 如果提供了上下文，以上下文为事实边界，不能奖励编造内容。
      3. 不要因为文风华丽而给高分，优先看事实、覆盖和可执行性。
      4. 只输出结构化 JSON，不要输出 Markdown 或解释性前后缀。
      """;

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;

  public EvalRunResponse.JudgeEvaluationResult evaluate(
      List<EvalRunRequest.JudgeCase> judgeCases) {
    if (judgeCases == null || judgeCases.isEmpty()) {
      return new EvalRunResponse.JudgeEvaluationResult(
          0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());
    }

    List<EvalRunResponse.JudgeItemResult> items = new ArrayList<>();
    int passedCount = 0;
    for (EvalRunRequest.JudgeCase judgeCase : judgeCases) {
      EvalRunResponse.JudgeItemResult item = evaluateCase(judgeCase);
      if (item.passed()) {
        passedCount++;
      }
      items.add(item);
    }

    int total = items.size();
    return new EvalRunResponse.JudgeEvaluationResult(
        total,
        passedCount,
        round(passedCount * 1.0 / total),
        average(items.stream().mapToDouble(EvalRunResponse.JudgeItemResult::overall).toArray()),
        average(items.stream().mapToDouble(EvalRunResponse.JudgeItemResult::relevance).toArray()),
        average(items.stream().mapToDouble(EvalRunResponse.JudgeItemResult::accuracy).toArray()),
        average(items.stream().mapToDouble(EvalRunResponse.JudgeItemResult::completeness).toArray()),
        average(items.stream().mapToDouble(EvalRunResponse.JudgeItemResult::helpfulness).toArray()),
        items);
  }

  private EvalRunResponse.JudgeItemResult evaluateCase(EvalRunRequest.JudgeCase judgeCase) {
    double minOverallScore = minOverallScoreOrDefault(judgeCase.minOverallScore());
    try {
      JudgeScore score = structuredOutputInvoker.invoke(
          llmProviderRegistry.getChatModelOrDefault(null),
          SYSTEM_PROMPT,
          buildUserPrompt(judgeCase),
          JudgeScore.class,
          ErrorCode.AI_SERVICE_ERROR,
          "LLM-as-Judge 评测失败: ",
          "[EvalRunJudge] ",
          log);
      double relevance = clamp(score.relevance());
      double accuracy = clamp(score.accuracy());
      double completeness = clamp(score.completeness());
      double helpfulness = clamp(score.helpfulness());
      double overall = round((relevance + accuracy + completeness + helpfulness) / 4.0);
      return new EvalRunResponse.JudgeItemResult(
          judgeCase.question(),
          minOverallScore,
          overall >= minOverallScore,
          relevance,
          accuracy,
          completeness,
          helpfulness,
          overall,
          safeText(score.reason(), "裁判模型未返回理由"),
          safeText(score.improvement(), "暂无改进建议"));
    } catch (Exception e) {
      log.warn("LLM-as-Judge 评测单条用例失败，按未通过处理: error={}", e.getMessage(), e);
      return new EvalRunResponse.JudgeItemResult(
          judgeCase.question(),
          minOverallScore,
          false,
          0.0,
          0.0,
          0.0,
          0.0,
          0.0,
          "LLM-as-Judge 评测失败: " + e.getMessage(),
          "检查模型连通性、裁判 prompt 或该用例输入长度");
    }
  }

  private String buildUserPrompt(EvalRunRequest.JudgeCase judgeCase) {
    return """
        # 用户问题
        %s

        # 候选回答
        %s

        # 参考答案（可为空）
        %s

        # 检索上下文或事实依据（可为空）
        %s
        """.formatted(
        truncate(judgeCase.question(), MAX_REFERENCE_CHARS),
        truncate(judgeCase.answer(), MAX_ANSWER_CHARS),
        truncate(judgeCase.referenceAnswer(), MAX_REFERENCE_CHARS),
        truncate(judgeCase.context(), MAX_CONTEXT_CHARS));
  }

  private double minOverallScoreOrDefault(Double minOverallScore) {
    if (minOverallScore == null || minOverallScore.isNaN() || minOverallScore.isInfinite()) {
      return DEFAULT_MIN_OVERALL_SCORE;
    }
    return clamp(minOverallScore);
  }

  private double average(double[] values) {
    if (values.length == 0) {
      return 0.0;
    }
    double sum = 0.0;
    for (double value : values) {
      sum += value;
    }
    return round(sum / values.length);
  }

  private double clamp(Double value) {
    if (value == null || value.isNaN() || value.isInfinite()) {
      return 0.0;
    }
    return round(Math.max(0.0, Math.min(1.0, value)));
  }

  private String safeText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String truncate(String value, int maxChars) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = value.strip();
    return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
  }

  private double round(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }

  private record JudgeScore(
      Double relevance,
      Double accuracy,
      Double completeness,
      Double helpfulness,
      String reason,
      String improvement
  ) {
  }
}
