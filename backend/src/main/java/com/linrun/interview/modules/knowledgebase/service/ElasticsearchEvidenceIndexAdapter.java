package com.linrun.interview.modules.knowledgebase.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.evidence.EvidenceIndexChunk;
import com.linrun.interview.common.evidence.EvidenceIndexPort;
import com.linrun.interview.common.evidence.EvidenceMetadata;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** 通用证据索引端口的单 ES 物理索引实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchEvidenceIndexAdapter implements EvidenceIndexPort {

  private final ElasticsearchEmbeddingStore embeddingStore;
  private final LlmProviderRegistry llmProviderRegistry;

  @Override
  public List<String> index(List<EvidenceIndexChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    List<TextSegment> segments = chunks.stream().map(this::toSegment).toList();
    EmbeddingModel embeddingModel = llmProviderRegistry.getDefaultEmbeddingModel();
    List<String> ids = new ArrayList<>(segments.size());
    try {
      for (int from = 0; from < segments.size(); from += EmbeddingBatchPolicy.MAX_BATCH_SIZE) {
        int to = Math.min(from + EmbeddingBatchPolicy.MAX_BATCH_SIZE, segments.size());
        List<TextSegment> batch = segments.subList(from, to);
        Response<List<Embedding>> embeddings = embeddingModel.embedAll(batch);
        List<Embedding> content = embeddings == null ? null : embeddings.content();
        if (content == null || content.size() != batch.size()) {
          throw new BusinessException(
              ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
              "Embedding 返回数量与请求片段数量不一致");
        }
        List<String> batchIds = embeddingStore.addAll(content, batch);
        if (batchIds == null || batchIds.size() != batch.size()) {
          throw new BusinessException(
              ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
              "向量索引返回数量与请求片段数量不一致");
        }
        ids.addAll(batchIds);
      }
    } catch (Exception e) {
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "证据向量写入失败", e);
    }
    if (ids.size() != chunks.size()) {
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "证据向量数量与片段数量不一致");
    }
    return List.copyOf(ids);
  }

  @Override
  public void delete(
      Long ownerUserId,
      DataDomain dataDomain,
      String resourceId,
      String resourceVersion
  ) {
    EvidenceMetadata guard = new EvidenceMetadata(
        ownerUserId,
        dataDomain,
        resourceId,
        resourceVersion == null || resourceVersion.isBlank() ? "*" : resourceVersion,
        "delete-guard",
        "delete-guard",
        "DELETE_GUARD",
        "delete-guard");
    Filter filter = metadataKey(MetadataKeyConstant.OWNER_USER_ID)
        .isEqualTo(String.valueOf(guard.ownerUserId()))
        .and(metadataKey(MetadataKeyConstant.DATA_DOMAIN).isEqualTo(guard.dataDomain().name()))
        .and(metadataKey(MetadataKeyConstant.RESOURCE_ID).isEqualTo(guard.resourceId()));
    if (resourceVersion != null && !resourceVersion.isBlank()) {
      filter = filter.and(metadataKey(MetadataKeyConstant.RESOURCE_VERSION)
          .isEqualTo(resourceVersion.trim()));
    }
    try {
      embeddingStore.removeAll(filter);
    } catch (Exception e) {
      if (isMissingIndex(e)) {
        // fresh ES 还没有物理索引时，replace 前的删除本就应是幂等成功。
        log.debug(
            "证据向量索引尚未创建，跳过删除: ownerUserId={}, dataDomain={}, resourceId={}, resourceVersion={}",
            ownerUserId, dataDomain, resourceId, resourceVersion);
        return;
      }
      log.error(
          "删除证据向量失败: ownerUserId={}, dataDomain={}, resourceId={}, resourceVersion={}",
          ownerUserId, dataDomain, resourceId, resourceVersion, e);
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "证据向量删除失败", e);
    }
  }

  private boolean isMissingIndex(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ElasticsearchException elasticsearchException
          && elasticsearchException.status() == 404
          && elasticsearchException.error() != null
          && "index_not_found_exception".equals(elasticsearchException.error().type())) {
        return true;
      }
    }
    return false;
  }

  private TextSegment toSegment(EvidenceIndexChunk chunk) {
    Map<String, Object> values = new HashMap<>(chunk.additionalMetadata());
    EvidenceMetadata metadata = chunk.metadata();
    values.put(MetadataKeyConstant.OWNER_USER_ID, String.valueOf(metadata.ownerUserId()));
    values.put(MetadataKeyConstant.DATA_DOMAIN, metadata.dataDomain().name());
    values.put(MetadataKeyConstant.RESOURCE_ID, metadata.resourceId());
    values.put(MetadataKeyConstant.RESOURCE_VERSION, metadata.resourceVersion());
    values.put(MetadataKeyConstant.EVIDENCE_ID, metadata.evidenceId());
    values.put(MetadataKeyConstant.CONTENT_HASH, metadata.contentHash());
    values.put(MetadataKeyConstant.SOURCE_TYPE, metadata.sourceType());
    values.put(MetadataKeyConstant.SOURCE_LOCATOR, metadata.sourceLocator());
    return TextSegment.from(chunk.text(), Metadata.from(values));
  }
}
