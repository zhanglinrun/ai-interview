package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.config.ElasticSearchProperties;
import com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import java.time.Duration;
import java.util.List;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("知识库流式查询失败 Trace")
class KnowledgeBaseQueryServiceStreamFailureTest {

  @AfterEach
  void clearUserContext() {
    UserContext.clear();
  }

  @Test
  @DisplayName("检索准备阶段异常也应保存一次失败 Trace")
  void setupFailurePersistsTrace() throws Exception {
    KnowledgeBaseCountService countService = mock(KnowledgeBaseCountService.class);
    RagQueryTraceService traceService = mock(RagQueryTraceService.class);
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getIntentRecognition().setEnabled(false);
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
        mock(IntentRecognitionService.class),
        mock(CommonChatService.class),
        traceService,
        mock(RagPromptService.class),
        mock(RagCardService.class),
        null);
    doThrow(new IllegalStateException("count unavailable"))
        .when(countService).updateQuestionCounts(List.of(1L));
    UserContext.setUserId(7L);

    List<String> chunks = service.answerQuestionStream(List.of(1L), "问题")
        .collectList()
        .block(Duration.ofSeconds(5));

    String errorAnswer = "【错误】知识库查询失败，请稍后重试";
    assertThat(chunks).containsExactly(errorAnswer);
    verify(traceService).save(
        eq(7L), eq(List.of(1L)), eq("问题"), any(RagQueryTrace.class),
        eq(List.of()), eq(errorAnswer), eq(0.0d), eq(List.of()), anyLong());
  }
}
