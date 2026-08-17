package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.InterviewIntent;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.model.LlmIntentRecognitionResult;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
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

  @Test
  @DisplayName("微服务/Agent 短问在 LLM 失败时仍应由规则和样例判为 TECH_KB")
  void backendAndAgentTermsHitTechKbWhenLlmFails() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenThrow(new IllegalStateException("model timeout"));

    IntentRecognitionResult microservice = service.recognize("微服务是什么呢");
    assertThat(microservice.related()).isTrue();
    assertThat(microservice.resolvedIntent()).isEqualTo(InterviewIntent.TECH_KB);
    assertThat(strategyConfidence(microservice, "rule")).isGreaterThan(0.0);
    assertThat(strategyConfidence(microservice, "vector")).isGreaterThan(0.2);

    IntentRecognitionResult agent = service.recognize("Agent 的 tool calling 怎么设计");
    assertThat(agent.related()).isTrue();
    assertThat(agent.resolvedIntent()).isEqualTo(InterviewIntent.TECH_KB);
    assertThat(strategyConfidence(agent, "rule")).isGreaterThan(0.0);

    IntentRecognitionResult rag = service.recognize("RAG 检索增强和重排怎么做");
    assertThat(rag.related()).isTrue();
    assertThat(rag.resolvedIntent()).isEqualTo(InterviewIntent.TECH_KB);
    assertThat(strategyConfidence(rag, "rule")).isGreaterThan(0.0);
  }

  @Test
  @DisplayName("扩充 TECH_KB 词表后，求职规划仍应由规则判为 CAREER")
  void expandedTechKeywordsDoNotStealCareer() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenThrow(new IllegalStateException("model timeout"));

    IntentRecognitionResult career = service.recognize("校招怎么规划");
    assertThat(career.related()).isTrue();
    assertThat(career.resolvedIntent()).isEqualTo(InterviewIntent.CAREER);
  }

  @Test
  @DisplayName("卡片追问文案应抽出简历 ID 与字符串会话 ID")
  void extractEntitiesFromCardFollowUpText() {
    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenReturn(new LlmIntentRecognitionResult(
            "简历分析",
            true,
            InterviewIntent.RESUME_STATS.name(),
            0.8,
            new IntentRecognitionResult.Entities(null, null, null, null)));

    IntentRecognitionResult resumeResult = service.recognize("请分析简历 ID=12（张三-阿里.pdf）");
    assertThat(resumeResult.entities()).isNotNull();
    assertThat(resumeResult.entities().resumeId()).isEqualTo(12L);

    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenReturn(new LlmIntentRecognitionResult(
            "面试报告",
            true,
            InterviewIntent.INTERVIEW_PREP.name(),
            0.8,
            new IntentRecognitionResult.Entities(null, null, null, null)));

    IntentRecognitionResult sessionResult = service.recognize(
        "请总结这场面试，会话 ID=abc123def456（java · 88分）");
    assertThat(sessionResult.entities()).isNotNull();
    assertThat(sessionResult.entities().sessionId()).isEqualTo("abc123def456");

    when(llmIntentRecognitionAiService.recognize(anyString()))
        .thenReturn(new LlmIntentRecognitionResult(
            "选择岗位方向",
            true,
            InterviewIntent.INTERVIEW_PREP.name(),
            0.8,
            new IntentRecognitionResult.Entities(null, null, null, null)));

    IntentRecognitionResult trackResult = service.recognize(
        "请针对「Java 后端」方向（jobTrack=java-backend）给出面试准备建议");
    assertThat(trackResult.entities()).isNotNull();
    assertThat(trackResult.entities().jobTrack()).isEqualTo("java-backend");
  }

  private static double strategyConfidence(IntentRecognitionResult result, String strategy) {
    return result.strategies().stream()
        .filter(score -> strategy.equals(score.strategy()))
        .mapToDouble(IntentRecognitionResult.StrategyScore::confidence)
        .findFirst()
        .orElse(-1.0);
  }
}
