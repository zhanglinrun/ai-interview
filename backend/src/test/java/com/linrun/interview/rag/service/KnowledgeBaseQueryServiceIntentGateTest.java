package com.linrun.interview.rag.service;import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.ai.service.RagPromptService;import com.linrun.interview.chat.service.CommonChatService;import com.linrun.interview.chat.service.RagCardService;import com.linrun.interview.document.service.KnowledgeBaseCountService;import com.linrun.interview.document.service.KnowledgeBaseListService;import com.linrun.interview.document.service.KnowledgeSegmentService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.config.ElasticSearchProperties;
import com.linrun.interview.chat.mapper.RagChatMessageMapper;
import com.linrun.interview.rag.model.IntentRecognitionResult;
import com.linrun.interview.rag.service.IntentRecognitionService;
import com.linrun.interview.rag.constant.InterviewIntent;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库意图门与闲聊分流")
class KnowledgeBaseQueryServiceIntentGateTest {

  @AfterEach
  void clearUserContext() {
    UserContext.clear();
  }

  @Test
  @DisplayName("越域问题应推送进度并走通用闲聊，不进入知识库检索")
  void offTopicRoutesToCommonChatWithoutRetrieval() throws Exception {
    KnowledgeBaseCountService countService = mock(KnowledgeBaseCountService.class);
    IntentRecognitionService intentRecognitionService = mock(IntentRecognitionService.class);
    CommonChatService commonChatService = mock(CommonChatService.class);
    RagCardService ragCardService = mock(RagCardService.class);

    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getIntentRecognition().setEnabled(true);
    properties.getIntentRecognition().setProgressEnabled(true);

    when(intentRecognitionService.recognize(anyString(), anyList()))
        .thenReturn(new IntentRecognitionResult(
            "与面试/技术知识无关",
            false,
            InterviewIntent.OFF_TOPIC.name(),
            null,
            0.91,
            List.of(
                new IntentRecognitionResult.StrategyScore("llm", "OFF_TOPIC", 0.9, 0.6, 0.54, "语义离题"),
                new IntentRecognitionResult.StrategyScore("vector", "OFF_TOPIC", 0.8, 0.25, 0.2, "样例"),
                new IntentRecognitionResult.StrategyScore("rule", "OFF_TOPIC", 0.7, 0.15, 0.105, "关键词")),
            false));
    when(commonChatService.streamChat(eq("今天天气怎么样"), eq(42L)))
        .thenReturn(Flux.just("今天天气不错，不过我更擅长回答面试相关问题。"));
    when(ragCardService.maybeInteractionCards(any(), anyString())).thenReturn(Optional.empty());

    KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
        mock(LlmProviderRegistry.class),
        mock(ElasticsearchEmbeddingStore.class),
        mock(RestClient.class),
        new ElasticSearchProperties(),
        mock(KnowledgeBaseListService.class),
        countService,
        mock(RerankService.class),
        mock(KnowledgeSegmentService.class),
        properties,
        new DefaultResourceLoader(),
        mock(RagChatMessageMapper.class),
        new ObjectMapper(),
        intentRecognitionService,
        commonChatService,
        mock(RagQueryTraceService.class),
        mock(RagPromptService.class),
        ragCardService,
        null);

    UserContext.setUserId(42L);

    List<String> chunks = service.answerQuestionStream(List.of(9L), "今天天气怎么样")
        .collectList()
        .block(Duration.ofSeconds(5));

    assertThat(chunks).isNotNull();
    assertThat(chunks).anyMatch(c -> c.startsWith("progress:") && c.contains("正在理解您的问题"));
    assertThat(chunks).anyMatch(c -> c.startsWith("intent:") && c.contains("\"intent\":\"OFF_TOPIC\""));
    assertThat(chunks).anyMatch(c -> c.startsWith("intent:") && c.contains("\"strategy\":\"llm\""));
    assertThat(chunks).anyMatch(c -> c.startsWith("progress:") && c.contains("正在生成回答"));
    assertThat(chunks).contains("今天天气不错，不过我更擅长回答面试相关问题。");
    assertThat(chunks).noneMatch(c -> c.startsWith("reference:"));
    assertThat(chunks).noneMatch(c -> c.startsWith("rewritten:"));

    verify(commonChatService).streamChat("今天天气怎么样", 42L);
    verify(intentRecognitionService).recognize(eq("今天天气怎么样"), anyList());
    verify(ragCardService, never()).maybeInteractionCards(any(), anyString());
  }

  @Test
  @DisplayName("相关问题不应短路到通用闲聊")
  void relatedQuestionDoesNotShortCircuitToCommonChat() throws Exception {
    IntentRecognitionService intentRecognitionService = mock(IntentRecognitionService.class);
    CommonChatService commonChatService = mock(CommonChatService.class);
    RagCardService ragCardService = mock(RagCardService.class);
    KnowledgeBaseListService listService = mock(KnowledgeBaseListService.class);

    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getIntentRecognition().setEnabled(true);

    when(intentRecognitionService.recognize(anyString(), anyList()))
        .thenReturn(new IntentRecognitionResult(
            "技术八股问题",
            true,
            InterviewIntent.TECH_KB.name(),
            null));
    when(ragCardService.maybeInteractionCards(any(), anyString())).thenReturn(Optional.empty());
    when(listService.getKnowledgeBaseNameMap(anyList()))
        .thenThrow(new IllegalStateException("stop-before-full-rag-for-test"));

    KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
        mock(LlmProviderRegistry.class),
        mock(ElasticsearchEmbeddingStore.class),
        mock(RestClient.class),
        new ElasticSearchProperties(),
        listService,
        mock(KnowledgeBaseCountService.class),
        mock(RerankService.class),
        mock(KnowledgeSegmentService.class),
        properties,
        new DefaultResourceLoader(),
        mock(RagChatMessageMapper.class),
        new ObjectMapper(),
        intentRecognitionService,
        commonChatService,
        mock(RagQueryTraceService.class),
        mock(RagPromptService.class),
        ragCardService,
        null);

    UserContext.setUserId(7L);

    List<String> chunks = service.answerQuestionStream(List.of(1L), "讲讲 JVM 垃圾回收原理")
        .collectList()
        .block(Duration.ofSeconds(5));

    assertThat(chunks).isNotNull();
    assertThat(chunks).isNotEmpty();
    verify(commonChatService, never()).streamChat(anyString(), any());
    verify(ragCardService).maybeInteractionCards(any(), eq("讲讲 JVM 垃圾回收原理"));
  }
}
