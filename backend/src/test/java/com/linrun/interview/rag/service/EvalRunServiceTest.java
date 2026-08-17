package com.linrun.interview.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.mapper.EvalRunMapper;
import com.linrun.interview.rag.model.EvalRunEntity;
import com.linrun.interview.rag.model.EvalRunRequest;
import com.linrun.interview.rag.model.EvalRunResponse;
import com.linrun.interview.rag.model.RagEvalRequest;
import com.linrun.interview.rag.model.RagEvalResponse;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.service.IntentRecognitionService;
import com.linrun.interview.rag.constant.InterviewIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("统一评测运行服务测试")
class EvalRunServiceTest {

  private final IntentRecognitionService intentRecognitionService = mock(IntentRecognitionService.class);
  private final RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
  private final LlmJudgeEvaluationService llmJudgeEvaluationService = mock(LlmJudgeEvaluationService.class);
  private final EvalRunMapper evalRunMapper = mock(EvalRunMapper.class);
  private final EvalRunService service = new EvalRunService(
      intentRecognitionService, ragEvaluationService, llmJudgeEvaluationService, evalRunMapper,
      new ObjectMapper().findAndRegisterModules());

  @Test
  @DisplayName("应计算意图识别准确率和 Macro-F1，并保存运行记录")
  void evaluateIntentCasesAndSaveRun() {
    when(intentRecognitionService.recognize("讲讲 JVM"))
        .thenReturn(intentResult(true, InterviewIntent.TECH_KB, 0.9));
    when(intentRecognitionService.recognize("今天天气怎么样"))
        .thenReturn(intentResult(false, InterviewIntent.OFF_TOPIC, 0.8));
    when(evalRunMapper.insert(any(EvalRunEntity.class))).thenReturn(1);

    EvalRunResponse response = runWithUser(new EvalRunRequest(
        "意图基线",
        "intent-basic",
        false,
        null,
        List.of(
            new EvalRunRequest.IntentCase("讲讲 JVM", InterviewIntent.TECH_KB.name(), null),
            new EvalRunRequest.IntentCase("今天天气怎么样", InterviewIntent.OFF_TOPIC.name(), null)),
        null));

    assertThat(response.runId()).startsWith("eval-");
    assertThat(response.intent().total()).isEqualTo(2);
    assertThat(response.intent().correct()).isEqualTo(2);
    assertThat(response.intent().accuracy()).isEqualTo(1.0);
    assertThat(response.intent().macroF1()).isEqualTo(1.0);
    assertThat(response.overallScore()).isEqualTo(1.0);
    assertThat(response.regression()).isFalse();
    assertThat(response.baselineComparison()).isNull();

    ArgumentCaptor<EvalRunEntity> entityCaptor = ArgumentCaptor.forClass(EvalRunEntity.class);
    verify(evalRunMapper).insert(entityCaptor.capture());
    assertThat(entityCaptor.getValue().getRunId()).isEqualTo(response.runId());
    assertThat(entityCaptor.getValue().getBaseline()).isFalse();
    assertThat(entityCaptor.getValue().getIntentAccuracy()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("应与最近基线对比并标记退化指标")
  void compareWithBaselineAndDetectRegression() {
    when(intentRecognitionService.recognize("讲讲 JVM"))
        .thenReturn(intentResult(false, InterviewIntent.OFF_TOPIC, 0.2));
    when(evalRunMapper.selectOne(any())).thenReturn(EvalRunEntity.builder()
        .runId("eval-baseline")
        .createdAt(LocalDateTime.now().minusDays(1))
        .overallScore(1.0)
        .intentAccuracy(1.0)
        .intentMacroF1(1.0)
        .build());
    when(evalRunMapper.insert(any(EvalRunEntity.class))).thenReturn(1);

    EvalRunResponse response = runWithUser(new EvalRunRequest(
        "意图回归",
        "intent-basic",
        false,
        0.05,
        List.of(new EvalRunRequest.IntentCase("讲讲 JVM", InterviewIntent.TECH_KB.name(), null)),
        null));

    assertThat(response.regression()).isTrue();
    assertThat(response.baselineComparison().baselineRunId()).isEqualTo("eval-baseline");
    assertThat(response.baselineComparison().metrics())
        .anySatisfy(metricDelta -> {
          assertThat(metricDelta.metric()).isEqualTo("overallScore");
          assertThat(metricDelta.regressed()).isTrue();
          assertThat(metricDelta.delta()).isLessThan(-0.05);
        });
  }

  @Test
  @DisplayName("应聚合 LLM-as-Judge 指标，并保存裁判评测运行记录")
  void evaluateJudgeCasesAndSaveRun() {
    when(llmJudgeEvaluationService.evaluate(any())).thenReturn(new EvalRunResponse.JudgeEvaluationResult(
        1,
        1,
        1.0,
        0.85,
        0.9,
        0.8,
        0.85,
        0.85,
        List.of(new EvalRunResponse.JudgeItemResult(
            "Redis 缓存穿透怎么解决？",
            0.75,
            true,
            0.9,
            0.8,
            0.85,
            0.85,
            0.85,
            "覆盖布隆过滤器和空值缓存",
            "可补充参数校验"))));
    when(evalRunMapper.insert(any(EvalRunEntity.class))).thenReturn(1);

    EvalRunResponse response = runWithUser(new EvalRunRequest(
        "裁判评测",
        "judge-basic",
        false,
        null,
        null,
        null,
        List.of(new EvalRunRequest.JudgeCase(
            "Redis 缓存穿透怎么解决？",
            "使用布隆过滤器和空值缓存。",
            "布隆过滤器、空值缓存、参数校验。",
            "缓存穿透指查询不存在的数据导致请求打到数据库。",
            0.75))));

    assertThat(response.intent()).isNull();
    assertThat(response.rag()).isNull();
    assertThat(response.judge().total()).isEqualTo(1);
    assertThat(response.judge().passed()).isEqualTo(1);
    assertThat(response.judge().averageOverall()).isEqualTo(0.85);
    assertThat(response.overallScore()).isEqualTo(0.85);

    ArgumentCaptor<EvalRunEntity> entityCaptor = ArgumentCaptor.forClass(EvalRunEntity.class);
    verify(evalRunMapper).insert(entityCaptor.capture());
    assertThat(entityCaptor.getValue().getJudgeTotal()).isEqualTo(1);
    assertThat(entityCaptor.getValue().getJudgePassed()).isEqualTo(1);
    assertThat(entityCaptor.getValue().getJudgePassRate()).isEqualTo(1.0);
    assertThat(entityCaptor.getValue().getJudgeAverageOverall()).isEqualTo(0.85);
  }

  @Test
  @DisplayName("关键词烟测的精确率门槛应按 |证据|/K 封顶，综合分纳入 Precision/Recall")
  void keywordSmokePrecisionGateIsCappedByAchievable() {
    when(ragEvaluationService.evaluate(any())).thenReturn(new RagEvalResponse(
        "rag-1",
        1,
        5,
        1.0,
        1.0,
        0.8,
        1.0,
        0.4,
        List.of(new RagEvalResponse.ItemResult(
            "什么是缓存穿透，如何防止",
            true,
            1,
            1.0,
            0.8,
            1.0,
            0.4,
            List.of("c1", "c2", "c3", "c4", "c5"),
            List.of(),
            2,
            List.of("不存在", "布隆过滤器"),
            List.of()))));
    when(evalRunMapper.insert(any(EvalRunEntity.class))).thenReturn(1);

    EvalRunResponse response = runWithUser(new EvalRunRequest(
        "检索烟测",
        "rag-smoke",
        false,
        null,
        null,
        new RagEvalRequest(
            List.of(1L),
            List.of(new RagEvalRequest.Item("什么是缓存穿透，如何防止", List.of("不存在", "布隆过滤器"), List.of())),
            5,
            "检索烟测")));

    assertThat(response.intent()).isNull();
    assertThat(response.judge()).isNull();
    assertThat(response.qualityGate().thresholds().get("retrievalPrecision")).isEqualTo(0.4);
    assertThat(response.qualityGate().passed()).isTrue();
    assertThat(response.overallScore()).isEqualTo(0.84);
  }

  private EvalRunResponse runWithUser(EvalRunRequest request) {
    UserContext.setUserId(7L);
    try {
      return service.run(request);
    } finally {
      UserContext.clear();
    }
  }

  private IntentRecognitionResult intentResult(boolean related, InterviewIntent intent, double confidence) {
    return new IntentRecognitionResult(
        "测试返回",
        related,
        intent.name(),
        null,
        confidence,
        List.of(),
        false);
  }
}
