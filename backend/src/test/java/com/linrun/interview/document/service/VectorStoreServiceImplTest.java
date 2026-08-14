package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.VectorStoreServiceImpl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.rag.config.ElasticSearchProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("知识库向量存储服务")
class VectorStoreServiceImplTest {

  private ElasticsearchEmbeddingStore embeddingStore;
  private EmbeddingModel embeddingModel;
  private VectorStoreServiceImpl service;

  @BeforeEach
  void setUp() {
    embeddingStore = mock(ElasticsearchEmbeddingStore.class);
    embeddingModel = mock(EmbeddingModel.class);
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    when(registry.getDefaultEmbeddingModel()).thenReturn(embeddingModel);
    ElasticSearchProperties properties = new ElasticSearchProperties();
    properties.setDimensions(1);
    service = new VectorStoreServiceImpl(embeddingStore, registry, new ObjectMapper(), properties);
  }

  @Test
  @DisplayName("批量写入使用 segment 主键生成稳定 ES ID")
  void shouldUseStableIdsForBatchUpsert() {
    KnowledgeBaseSegmentEntity first = segment(11L, "第一段");
    KnowledgeBaseSegmentEntity second = segment(12L, "第二段");
    when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
        Embedding.from(new float[] {1.0f}),
        Embedding.from(new float[] {2.0f}))));

    List<String> result = service.embedAndStore(List.of(first, second), "claim-1");

    assertThat(result).containsExactly("kb-segment-11", "kb-segment-12");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> idCaptor = ArgumentCaptor.forClass(List.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TextSegment>> segmentCaptor = ArgumentCaptor.forClass(List.class);
    verify(embeddingStore).addAll(
        idCaptor.capture(), anyList(), segmentCaptor.capture());
    assertThat(idCaptor.getValue()).containsExactly("kb-segment-11", "kb-segment-12");
    assertThat(segmentCaptor.getValue()).allSatisfy(segment ->
        assertThat(segment.metadata().getString("embeddingClaim")).isEqualTo("claim-1"));
  }

  @Test
  @DisplayName("失败批次仅按租约令牌过滤删除")
  void shouldRemoveOnlyMatchingEmbeddingClaim() {
    service.removeByEmbeddingClaim("claim-1");

    verify(embeddingStore).removeAll(any(Filter.class));
  }

  @Test
  @DisplayName("重试同一分段时复用同一个 ES ID")
  void shouldReuseStableIdOnRetry() {
    KnowledgeBaseSegmentEntity segment = segment(21L, "可重试分段");
    when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
        Embedding.from(new float[] {1.0f}))));

    assertThat(service.embedAndStore(List.of(segment))).containsExactly("kb-segment-21");
    assertThat(service.embedAndStore(List.of(segment))).containsExactly("kb-segment-21");
  }

  @Test
  @DisplayName("未落库分段不能生成随机向量 ID")
  void shouldRejectSegmentWithoutDatabaseId() {
    KnowledgeBaseSegmentEntity segment = segment(null, "未落库");

    assertThatThrownBy(() -> service.embedAndStore(List.of(segment)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("分段尚未落库");
  }

  @Test
  @DisplayName("Embedding 维度与 ES mapping 不一致时写入前失败")
  void shouldRejectDimensionMismatchBeforeIndexing() {
    KnowledgeBaseSegmentEntity segment = segment(31L, "维度不匹配");
    when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
        Embedding.from(new float[] {1.0f, 2.0f}))));

    assertThatThrownBy(() -> service.embedAndStore(List.of(segment)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("维度与 ES 索引不一致");
  }

  private KnowledgeBaseSegmentEntity segment(Long id, String text) {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setId(id);
    segment.setText(text);
    segment.setMetadata("{}");
    return segment;
  }
}
