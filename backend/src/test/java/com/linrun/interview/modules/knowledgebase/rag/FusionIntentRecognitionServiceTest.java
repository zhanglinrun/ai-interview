package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("三路融合意图识别服务测试")
class FusionIntentRecognitionServiceTest {

  private final LlmIntentRecognitionAiService llmIntentRecognitionAiService =
      mock(LlmIntentRecognitionAiService.class);
  private final KnowledgeBaseQueryProperties queryProperties = new KnowledgeBaseQueryProperties();
  private final FusionIntentRecognitionService service = new FusionIntentRecognitionService(
      llmIntentRecognitionAiService, queryProperties);

  @Test
  @DisplayName("应融合 LLM、样例相似度和规则证据，并缓存相同问题")
  void recognizeWithFusionAndCache() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenReturn(new LlmIntentRecognitionResult(
            "问题在询问 JVM 垃圾回收原理",
            true,
            InterviewIntent.TECH_KB.name(),
            0.9,
            new IntentRecognitionResult.Entities("java", null, null, null)));

    IntentRecognitionResult firstResult = service.recognize("讲讲 JVM 垃圾回收原理");
    IntentRecognitionResult cachedResult = service.recognize("讲讲 JVM 垃圾回收原理");

    assertThat(firstResult.related()).isTrue();
    assertThat(firstResult.resolvedIntent()).isEqualTo(InterviewIntent.TECH_KB);
    assertThat(firstResult.confidence()).isGreaterThan(0.0);
    assertThat(firstResult.cached()).isFalse();
    assertThat(firstResult.strategies())
        .extracting(IntentRecognitionResult.StrategyScore::strategy)
        .containsExactly("llm", "vector", "rule");

    assertThat(cachedResult.cached()).isTrue();
    assertThat(cachedResult.resolvedIntent()).isEqualTo(InterviewIntent.TECH_KB);
    verify(llmIntentRecognitionAiService, times(1)).recognize(anyString());
  }

  @Test
  @DisplayName("LLM 分支失败时应继续使用本地相似度和规则兜底")
  void fallbackWhenLlmFails() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenThrow(new IllegalStateException("model timeout"));

    IntentRecognitionResult result = service.recognize("这道 LeetCode 怎么优化");

    assertThat(result.related()).isTrue();
    assertThat(result.resolvedIntent()).isEqualTo(InterviewIntent.CODE_REVIEW);
    assertThat(result.confidence()).isGreaterThanOrEqualTo(
        queryProperties.getIntentRecognition().getMinConfidence());
    assertThat(result.strategies())
        .anySatisfy(strategyScore -> {
          assertThat(strategyScore.strategy()).isEqualTo("llm");
          assertThat(strategyScore.confidence()).isZero();
        });
  }

  @Test
  @DisplayName("明显越域闲聊应判为 OFF_TOPIC 且 related=false")
  void offTopicIdleChatMarkedUnrelated() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenReturn(new LlmIntentRecognitionResult(
            "天气闲聊，与面试无关",
            false,
            InterviewIntent.OFF_TOPIC.name(),
            0.95,
            null));

    IntentRecognitionResult result = service.recognize("今天天气怎么样");

    assertThat(result.related()).isFalse();
    assertThat(result.resolvedIntent()).isEqualTo(InterviewIntent.OFF_TOPIC);
    assertThat(result.confidence()).isGreaterThan(0.0);
  }
}
