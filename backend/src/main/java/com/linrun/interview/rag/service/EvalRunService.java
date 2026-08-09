package com.linrun.interview.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.mapper.EvalRunMapper;
import com.linrun.interview.rag.model.EvalRunEntity;
import com.linrun.interview.rag.model.EvalRunRequest;
import com.linrun.interview.rag.model.EvalRunResponse;
import com.linrun.interview.rag.model.RagEvalResponse;
import com.linrun.interview.rag.model.EvalRunSummary;
import com.linrun.interview.rag.config.EvalQualityProperties;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.service.IntentRecognitionService;
import com.linrun.interview.rag.constant.InterviewIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 统一评测运行服务。
 *
 * <p>把「意图识别评测」和已有「RAG 检索评测」合并成一次可持久化运行，并与同一
 * baselineKey 下的最近基线做指标回归判断，避免只有功能展示、没有工程闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

  private static final String DEFAULT_TITLE = "统一评测运行";
  private static final String DEFAULT_BASELINE_KEY = "default";
  private static final double DEFAULT_REGRESSION_THRESHOLD = 0.05;
  private static final String RELATED_LABEL = "RELATED";

  private final IntentRecognitionService intentRecognitionService;
  private final RagEvaluationService ragEvaluationService;
  private final LlmJudgeEvaluationService llmJudgeEvaluationService;
  private final EvalRunMapper evalRunMapper;
  private final ObjectMapper objectMapper;
  private final EvalQualityProperties qualityProperties;

  @Autowired
  public EvalRunService(IntentRecognitionService intentRecognitionService,
                        RagEvaluationService ragEvaluationService,
                        LlmJudgeEvaluationService llmJudgeEvaluationService,
                        EvalRunMapper evalRunMapper,
                        ObjectMapper objectMapper) {
    this(intentRecognitionService, ragEvaluationService, llmJudgeEvaluationService,
        evalRunMapper, objectMapper, new EvalQualityProperties());
  }

  public EvalRunResponse run(EvalRunRequest request) {
    validateRequest(request);

    Long userId = UserContext.requireUserId();
    String runId = "eval-" + UUID.randomUUID();
    String title = titleOrDefault(request.title());
    String baselineKey = baselineKeyOrDefault(request.baselineKey());
    boolean baseline = Boolean.TRUE.equals(request.updateBaseline());
    double threshold = thresholdOrDefault(request.regressionThreshold());
    LocalDateTime createdAt = LocalDateTime.now();

    EvalRunResponse.IntentEvaluationResult intent = evaluateIntent(request.intentCases());
    RagEvalResponse rag = request.rag() == null ? null : ragEvaluationService.evaluate(request.rag());
    EvalRunResponse.JudgeEvaluationResult judge = evaluateJudge(request.judgeCases());
    double overallScore = calculateOverallScore(intent, rag, judge);
    EvalRunResponse.QualityGate qualityGate = evaluateQualityGate(intent, rag, judge);

    EvalRunEntity baselineEntity = findLatestBaseline(userId, baselineKey);
    EvalRunResponse.BaselineComparison baselineComparison = compareWithBaseline(
        baselineEntity, threshold, intent, rag, judge, overallScore);
    boolean regression = baselineComparison != null
        && baselineComparison.metrics().stream().anyMatch(EvalRunResponse.MetricDelta::regressed);

    EvalRunResponse response = new EvalRunResponse(
        runId, title, baselineKey, baseline, overallScore, regression, intent, rag,
        judge, baselineComparison, qualityGate, createdAt);
    saveRun(userId, request, response, threshold);
    return response;
  }

  public List<EvalRunSummary> listRecent(int limit) {
    int safeLimit = Math.min(Math.max(limit, 1), 100);
    return evalRunMapper.selectList(Wrappers.<EvalRunEntity>lambdaQuery()
        .eq(EvalRunEntity::getUserId, UserContext.requireUserId())
        .orderByDesc(EvalRunEntity::getCreatedAt)
        .last("LIMIT " + safeLimit))
        .stream().map(entity -> EvalRunSummary.from(entity, objectMapper)).toList();
  }

  public EvalRunResponse get(String runId) {
    EvalRunEntity entity = evalRunMapper.selectOne(Wrappers.<EvalRunEntity>lambdaQuery()
        .eq(EvalRunEntity::getUserId, UserContext.requireUserId())
        .eq(EvalRunEntity::getRunId, runId));
    if (entity == null || entity.getResponseJson() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在: " + runId);
    }
    try {
      return objectMapper.readValue(entity.getResponseJson(), EvalRunResponse.class);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评测结果已损坏", e);
    }
  }

  private EvalRunResponse.QualityGate evaluateQualityGate(
      EvalRunResponse.IntentEvaluationResult intent,
      RagEvalResponse rag,
      EvalRunResponse.JudgeEvaluationResult judge) {
    java.util.Map<String, Double> metrics = new java.util.LinkedHashMap<>();
    java.util.Map<String, Double> thresholds = new java.util.LinkedHashMap<>();
    java.util.List<String> failures = new java.util.ArrayList<>();
    if (intent != null && intent.total() > 0) {
      addGateMetric(metrics, thresholds, failures, "intentAccuracy", intent.accuracy(),
          qualityProperties.getIntentAccuracy());
      addGateMetric(metrics, thresholds, failures, "intentMacroF1", intent.macroF1(),
          qualityProperties.getIntentMacroF1());
    }
    if (rag != null && rag.total() > 0) {
      addGateMetric(metrics, thresholds, failures, "retrievalRecall", rag.retrievalRecall(),
          qualityProperties.getRetrievalRecall());
      addGateMetric(metrics, thresholds, failures, "retrievalMrr", rag.mrr(),
          qualityProperties.getRetrievalMrr());
      addGateMetric(metrics, thresholds, failures, "retrievalNdcg", rag.ndcg(),
          qualityProperties.getRetrievalNdcg());
      addGateMetric(metrics, thresholds, failures, "citationCoverage", rag.citationCoverage(),
          qualityProperties.getCitationCoverage());
    }
    if (judge != null && judge.total() > 0) {
      // The judge's factual accuracy is the offline groundedness proxy when
      // the run does not execute a full answer+citation generation pass.
      addGateMetric(metrics, thresholds, failures, "groundedness", judge.averageAccuracy(),
          qualityProperties.getGroundedness());
      addGateMetric(metrics, thresholds, failures, "answerQuality", judge.averageOverall(),
          qualityProperties.getAnswerQuality());
      addGateMetric(metrics, thresholds, failures, "answerRelevance", judge.averageRelevance(),
          qualityProperties.getAnswerQuality());
      addGateMetric(metrics, thresholds, failures, "answerAccuracy", judge.averageAccuracy(),
          qualityProperties.getAnswerQuality());
      addGateMetric(metrics, thresholds, failures, "answerCompleteness", judge.averageCompleteness(),
          qualityProperties.getAnswerQuality());
      addGateMetric(metrics, thresholds, failures, "answerHelpfulness", judge.averageHelpfulness(),
          qualityProperties.getAnswerQuality());
    }
    return new EvalRunResponse.QualityGate(failures.isEmpty(), metrics, thresholds, failures);
  }

  private void addGateMetric(java.util.Map<String, Double> metrics,
                             java.util.Map<String, Double> thresholds,
                             java.util.List<String> failures,
                             String name, double value, double threshold) {
    double roundedValue = round(value);
    double roundedThreshold = round(threshold);
    metrics.put(name, roundedValue);
    thresholds.put(name, roundedThreshold);
    if (roundedValue < roundedThreshold) {
      failures.add(name + "=" + roundedValue + " < " + roundedThreshold);
    }
  }

  private EvalRunResponse.IntentEvaluationResult evaluateIntent(
      List<EvalRunRequest.IntentCase> intentCases) {
    if (intentCases == null || intentCases.isEmpty()) {
      return new EvalRunResponse.IntentEvaluationResult(0, 0, 0.0, 0.0, List.of());
    }

    List<EvalRunResponse.IntentItemResult> items = new ArrayList<>();
    int correctCount = 0;
    for (EvalRunRequest.IntentCase intentCase : intentCases) {
      EvalRunResponse.IntentItemResult item = evaluateIntentCase(intentCase);
      if (item.correct()) {
        correctCount++;
      }
      items.add(item);
    }

    double accuracy = intentCases.isEmpty() ? 0.0 : correctCount * 1.0 / intentCases.size();
    return new EvalRunResponse.IntentEvaluationResult(
        intentCases.size(), correctCount, round(accuracy), calculateMacroF1(items), items);
  }

  private EvalRunResponse.IntentItemResult evaluateIntentCase(EvalRunRequest.IntentCase intentCase) {
    String expectedIntent = normalizeExpectedIntent(intentCase.expectedIntent());
    Boolean expectedRelated = expectedRelatedOrDefault(expectedIntent, intentCase.expectedRelated());
    IntentRecognitionResult actualResult = recognizeSafely(intentCase.question());
    String actualIntent = actualResult == null ? InterviewIntent.OFF_TOPIC.name() : actualResult.resolvedIntent().name();
    boolean actualRelated = actualResult != null && actualResult.related();
    boolean intentMatches = expectedIntent == null || expectedIntent.equals(actualIntent);
    boolean relatedMatches = expectedRelated == null || expectedRelated.equals(actualRelated);
    boolean correct = intentMatches && relatedMatches;

    return new EvalRunResponse.IntentItemResult(
        intentCase.question(), expectedIntent, expectedRelated, actualIntent, actualRelated,
        confidenceOrZero(actualResult), correct, reasonOrDefault(actualResult), strategiesOrEmpty(actualResult));
  }

  private IntentRecognitionResult recognizeSafely(String question) {
    try {
      return intentRecognitionService.recognize(question);
    } catch (Exception e) {
      log.warn("统一评测运行中意图识别失败，按该用例未命中处理: question={}", question, e);
      return new IntentRecognitionResult(
          "意图识别失败: " + e.getMessage(), false, InterviewIntent.OFF_TOPIC.name(), null,
          0.0, List.of(), false);
    }
  }

  private double calculateMacroF1(List<EvalRunResponse.IntentItemResult> items) {
    if (items == null || items.isEmpty()) {
      return 0.0;
    }
    Set<String> labels = new LinkedHashSet<>();
    for (EvalRunResponse.IntentItemResult item : items) {
      labels.add(expectedLabel(item));
      labels.add(actualLabel(item));
    }

    double totalF1 = 0.0;
    for (String label : labels) {
      int truePositive = 0;
      int falsePositive = 0;
      int falseNegative = 0;
      for (EvalRunResponse.IntentItemResult item : items) {
        String expectedLabel = expectedLabel(item);
        String actualLabel = actualLabel(item);
        if (label.equals(expectedLabel) && label.equals(actualLabel)) {
          truePositive++;
        } else if (!label.equals(expectedLabel) && label.equals(actualLabel)) {
          falsePositive++;
        } else if (label.equals(expectedLabel)) {
          falseNegative++;
        }
      }
      double precision = truePositive + falsePositive == 0
          ? 0.0
          : truePositive * 1.0 / (truePositive + falsePositive);
      double recall = truePositive + falseNegative == 0
          ? 0.0
          : truePositive * 1.0 / (truePositive + falseNegative);
      totalF1 += precision + recall == 0.0 ? 0.0 : 2 * precision * recall / (precision + recall);
    }
    return round(totalF1 / labels.size());
  }

  private EvalRunResponse.JudgeEvaluationResult evaluateJudge(
      List<EvalRunRequest.JudgeCase> judgeCases) {
    if (judgeCases == null || judgeCases.isEmpty()) {
      return new EvalRunResponse.JudgeEvaluationResult(
          0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());
    }
    return llmJudgeEvaluationService.evaluate(judgeCases);
  }

  private double calculateOverallScore(EvalRunResponse.IntentEvaluationResult intent,
                                       RagEvalResponse rag,
                                       EvalRunResponse.JudgeEvaluationResult judge) {
    List<Double> availableScores = new ArrayList<>();
    if (intent != null && intent.total() > 0) {
      availableScores.add(intent.accuracy());
    }
    if (rag != null) {
      availableScores.add(round(rag.hitRate() * 0.5 + rag.mrr() * 0.3 + rag.ndcg() * 0.2));
    }
    if (judge != null && judge.total() > 0) {
      availableScores.add(judge.averageOverall());
    }
    if (availableScores.isEmpty()) {
      return 0.0;
    }
    return round(availableScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
  }

  private EvalRunResponse.BaselineComparison compareWithBaseline(
      EvalRunEntity baselineEntity,
      double threshold,
      EvalRunResponse.IntentEvaluationResult intent,
      RagEvalResponse rag,
      EvalRunResponse.JudgeEvaluationResult judge,
      double overallScore) {
    if (baselineEntity == null) {
      return null;
    }

    List<EvalRunResponse.MetricDelta> metrics = new ArrayList<>();
    addMetric(metrics, "overallScore", overallScore, baselineEntity.getOverallScore(), threshold);
    if (intent != null && intent.total() > 0) {
      addMetric(metrics, "intentAccuracy", intent.accuracy(), baselineEntity.getIntentAccuracy(), threshold);
      addMetric(metrics, "intentMacroF1", intent.macroF1(), baselineEntity.getIntentMacroF1(), threshold);
    }
    if (rag != null) {
      addMetric(metrics, "ragHitRate", rag.hitRate(), baselineEntity.getRagHitRate(), threshold);
      addMetric(metrics, "ragMrr", rag.mrr(), baselineEntity.getRagMrr(), threshold);
      addMetric(metrics, "ragNdcg", rag.ndcg(), baselineEntity.getRagNdcg(), threshold);
    }
    if (judge != null && judge.total() > 0) {
      addMetric(metrics, "judgePassRate", judge.passRate(), baselineEntity.getJudgePassRate(), threshold);
      addMetric(metrics, "judgeAverageOverall", judge.averageOverall(),
          baselineEntity.getJudgeAverageOverall(), threshold);
      addMetric(metrics, "judgeAverageRelevance", judge.averageRelevance(),
          baselineEntity.getJudgeAverageRelevance(), threshold);
      addMetric(metrics, "judgeAverageAccuracy", judge.averageAccuracy(),
          baselineEntity.getJudgeAverageAccuracy(), threshold);
      addMetric(metrics, "judgeAverageCompleteness", judge.averageCompleteness(),
          baselineEntity.getJudgeAverageCompleteness(), threshold);
      addMetric(metrics, "judgeAverageHelpfulness", judge.averageHelpfulness(),
          baselineEntity.getJudgeAverageHelpfulness(), threshold);
    }
    return new EvalRunResponse.BaselineComparison(
        baselineEntity.getRunId(), baselineEntity.getCreatedAt(), threshold, metrics);
  }

  private void addMetric(List<EvalRunResponse.MetricDelta> metrics, String metric, double current,
                         Double baseline, double threshold) {
    if (baseline == null) {
      return;
    }
    double delta = round(current - baseline);
    metrics.add(new EvalRunResponse.MetricDelta(
        metric, round(current), round(baseline), delta, delta < -threshold));
  }

  private EvalRunEntity findLatestBaseline(Long userId, String baselineKey) {
    return evalRunMapper.selectOne(Wrappers.lambdaQuery(EvalRunEntity.class)
        .eq(EvalRunEntity::getUserId, userId)
        .eq(EvalRunEntity::getBaselineKey, baselineKey)
        .eq(EvalRunEntity::getBaseline, true)
        .orderByDesc(EvalRunEntity::getCreatedAt)
        .last("LIMIT 1"));
  }

  private void saveRun(Long userId, EvalRunRequest request, EvalRunResponse response, double threshold) {
    try {
      evalRunMapper.insert(EvalRunEntity.builder()
          .userId(userId)
          .runId(response.runId())
          .title(response.title())
          .baselineKey(response.baselineKey())
          .baseline(response.baseline())
          .requestJson(objectMapper.writeValueAsString(request))
          .responseJson(objectMapper.writeValueAsString(response))
          .intentTotal(response.intent() == null ? 0 : response.intent().total())
          .intentCorrect(response.intent() == null ? 0 : response.intent().correct())
          .intentAccuracy(response.intent() == null ? 0.0 : response.intent().accuracy())
          .intentMacroF1(response.intent() == null ? 0.0 : response.intent().macroF1())
          .ragRunId(response.rag() == null ? null : response.rag().runId())
          .ragHitRate(response.rag() == null ? null : response.rag().hitRate())
          .ragMrr(response.rag() == null ? null : response.rag().mrr())
          .ragNdcg(response.rag() == null ? null : response.rag().ndcg())
          .judgeTotal(response.judge() == null ? 0 : response.judge().total())
          .judgePassed(response.judge() == null ? 0 : response.judge().passed())
          .judgePassRate(response.judge() == null ? 0.0 : response.judge().passRate())
          .judgeAverageOverall(response.judge() == null ? 0.0 : response.judge().averageOverall())
          .judgeAverageRelevance(response.judge() == null ? 0.0 : response.judge().averageRelevance())
          .judgeAverageAccuracy(response.judge() == null ? 0.0 : response.judge().averageAccuracy())
          .judgeAverageCompleteness(response.judge() == null ? 0.0 : response.judge().averageCompleteness())
          .judgeAverageHelpfulness(response.judge() == null ? 0.0 : response.judge().averageHelpfulness())
          .overallScore(response.overallScore())
          .regression(response.regression())
          .regressionThreshold(threshold)
          .createdAt(response.createdAt())
          .build());
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "保存统一评测运行结果失败", e);
    }
  }

  private void validateRequest(EvalRunRequest request) {
    if (request == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "评测请求不能为空");
    }
    boolean hasIntentCases = request.intentCases() != null && !request.intentCases().isEmpty();
    boolean hasRagCases = request.rag() != null;
    boolean hasJudgeCases = request.judgeCases() != null && !request.judgeCases().isEmpty();
    if (!hasIntentCases && !hasRagCases && !hasJudgeCases) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "请至少提供意图识别用例、RAG 评测用例或 LLM-as-Judge 评测用例");
    }
    if (hasIntentCases) {
      for (EvalRunRequest.IntentCase intentCase : request.intentCases()) {
        if (intentCase.expectedRelated() == null && isBlank(intentCase.expectedIntent())) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "意图识别用例需提供 expectedIntent 或 expectedRelated");
        }
        normalizeExpectedIntent(intentCase.expectedIntent());
      }
    }
    if (hasJudgeCases) {
      for (EvalRunRequest.JudgeCase judgeCase : request.judgeCases()) {
        if (isBlank(judgeCase.question()) || isBlank(judgeCase.answer())) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "LLM-as-Judge 用例需提供 question 和 answer");
        }
      }
    }
  }

  private String normalizeExpectedIntent(String rawIntent) {
    if (isBlank(rawIntent)) {
      return null;
    }
    try {
      return InterviewIntent.valueOf(rawIntent.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的意图类型: " + rawIntent);
    }
  }

  private Boolean expectedRelatedOrDefault(String expectedIntent, Boolean expectedRelated) {
    if (expectedRelated != null) {
      return expectedRelated;
    }
    if (expectedIntent == null) {
      return null;
    }
    return !InterviewIntent.OFF_TOPIC.name().equals(expectedIntent);
  }

  private String expectedLabel(EvalRunResponse.IntentItemResult item) {
    if (!isBlank(item.expectedIntent())) {
      return item.expectedIntent();
    }
    if (Boolean.TRUE.equals(item.expectedRelated())) {
      return RELATED_LABEL;
    }
    return InterviewIntent.OFF_TOPIC.name();
  }

  private String actualLabel(EvalRunResponse.IntentItemResult item) {
    if (item.expectedIntent() == null && Boolean.TRUE.equals(item.expectedRelated())) {
      return item.actualRelated() ? RELATED_LABEL : InterviewIntent.OFF_TOPIC.name();
    }
    return item.actualIntent();
  }

  private String titleOrDefault(String title) {
    if (isBlank(title)) {
      return DEFAULT_TITLE;
    }
    return title.length() <= 120 ? title : title.substring(0, 120);
  }

  private String baselineKeyOrDefault(String baselineKey) {
    if (isBlank(baselineKey)) {
      return DEFAULT_BASELINE_KEY;
    }
    return baselineKey.length() <= 80 ? baselineKey : baselineKey.substring(0, 80);
  }

  private double thresholdOrDefault(Double threshold) {
    if (threshold == null || threshold.isNaN() || threshold.isInfinite() || threshold < 0) {
      return DEFAULT_REGRESSION_THRESHOLD;
    }
    return threshold;
  }

  private double confidenceOrZero(IntentRecognitionResult result) {
    return result == null || result.confidence() == null ? 0.0 : round(result.confidence());
  }

  private String reasonOrDefault(IntentRecognitionResult result) {
    return result == null || result.reason() == null ? "未返回识别结果" : result.reason();
  }

  private List<IntentRecognitionResult.StrategyScore> strategiesOrEmpty(IntentRecognitionResult result) {
    return result == null || result.strategies() == null ? List.of() : result.strategies();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private double round(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }
}
