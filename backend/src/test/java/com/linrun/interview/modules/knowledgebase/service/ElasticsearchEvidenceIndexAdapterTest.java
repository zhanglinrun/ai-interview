package com.linrun.interview.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

@DisplayName("Elasticsearch 证据索引适配器")
class ElasticsearchEvidenceIndexAdapterTest {

  private ElasticsearchEmbeddingStore embeddingStore;
  private LlmProviderRegistry llmProviderRegistry;
  private ElasticsearchEvidenceIndexAdapter adapter;

  @BeforeEach
  void setUp() {
    embeddingStore = mock(ElasticsearchEmbeddingStore.class);
    llmProviderRegistry = mock(LlmProviderRegistry.class);
    adapter = new ElasticsearchEvidenceIndexAdapter(embeddingStore, llmProviderRegistry);
  }

  @Test
  @DisplayName("fresh ES 索引不存在时删除幂等成功")
  void shouldIgnoreMissingIndex() {
    doThrow(elasticsearchFailure(404, "index_not_found_exception"))
        .when(embeddingStore).removeAll(any(Filter.class));

    assertThatCode(() -> adapter.delete(
        7L, DataDomain.GITHUB, "github-repository:9", "a".repeat(40)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("其他 Elasticsearch 删除异常仍显式失败")
  void shouldNotHideOtherElasticsearchFailures() {
    ElasticsearchException failure = elasticsearchFailure(503, "unavailable_shards_exception");
    doThrow(failure).when(embeddingStore).removeAll(any(Filter.class));

    assertThatThrownBy(() -> adapter.delete(
        7L, DataDomain.GITHUB, "github-repository:9", "a".repeat(40)))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode())
              .isEqualTo(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED.getCode());
          assertThat(exception.getCause()).isSameAs(failure);
        });
  }

  @Test
  @DisplayName("统一证据索引按安全批次写入并保持返回数量")
  void shouldUseSafeEmbeddingBatches() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(llmProviderRegistry.getDefaultEmbeddingModel()).thenReturn(embeddingModel);
    List<TextSegment> requested = new ArrayList<>();
    for (int index = 0; index < 11; index++) {
      requested.add(TextSegment.from("chunk-" + index));
    }
    when(embeddingModel.embedAll(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(invocation -> {
          List<TextSegment> batch = invocation.getArgument(0);
          return Response.from(batch.stream()
              .map(item -> Embedding.from(new float[] {1.0f}))
              .toList());
        });
    when(embeddingStore.addAll(org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(invocation -> {
          List<?> batch = invocation.getArgument(0);
          return java.util.stream.IntStream.range(0, batch.size())
              .mapToObj(index -> "embedding-" + index).toList();
        });

    assertThat(adapter.index(requested.stream()
        .map(segment -> new com.linrun.interview.common.evidence.EvidenceIndexChunk(
            segment.text(), new com.linrun.interview.common.evidence.EvidenceMetadata(
                7L, DataDomain.GITHUB, "github-repository:9", "a".repeat(40),
                "evidence-" + segment.text(), "c".repeat(64), "GITHUB_CODE",
                "https://github.com/demo/repo"), java.util.Map.of()))
        .toList())).hasSize(11);
    verify(embeddingModel, times(2)).embedAll(org.mockito.ArgumentMatchers.anyList());
  }

  private ElasticsearchException elasticsearchFailure(int status, String type) {
    ErrorResponse response = ErrorResponse.of(builder -> builder
        .status(status)
        .error(error -> error.type(type).reason(type)));
    return new ElasticsearchException("delete_by_query", response);
  }
}
