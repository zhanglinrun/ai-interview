package com.linrun.interview.document.service;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.rag.config.ElasticSearchProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.document.service.EmbeddingBatchPolicy;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量存储服务实现（对齐业界实践 VectorStoreServiceImpl）。
 *
 * <p>与早期实现差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>embedding model 通过 {@link LlmProviderRegistry#getDefaultEmbeddingModel()} 获取，不直接 new OpenAiEmbeddingModel。</li>
 *   <li>metadata 从 segment 的 JSON 字符串解析（{@link KnowledgeBaseSegmentEntity#getMetadata()}），用 Jackson。</li>
 *   <li>数量不一致抛 {@link BusinessException}，不用 Spring Assert（Assert 抒 IllegalArgument，绕过全局异常处理）。</li>
 * </ul>
 */
@Slf4j
@Service
public class VectorStoreServiceImpl implements VectorStoreService {

    private static final String DOC_ID_KEY = "docId";
    private static final String VERSION_KEY = "version";
    private static final String EMBEDDING_ID_PREFIX = "kb-segment-";
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;
    private final ElasticSearchProperties elasticSearchProperties;

    public VectorStoreServiceImpl(
        ElasticsearchEmbeddingStore embeddingStore,
        LlmProviderRegistry llmProviderRegistry,
        ObjectMapper objectMapper,
        ElasticSearchProperties elasticSearchProperties
    ) {
        this.embeddingStore = embeddingStore;
        this.llmProviderRegistry = llmProviderRegistry;
        this.objectMapper = objectMapper;
        this.elasticSearchProperties = elasticSearchProperties;
    }

    private EmbeddingModel embeddingModel() {
        return llmProviderRegistry.getDefaultEmbeddingModel();
    }

    @Override
    public List<String> embedAndStore(List<KnowledgeBaseSegmentEntity> segments) {
        return embedAndStore(segments, null);
    }

    @Override
    public List<String> embedAndStore(
        List<KnowledgeBaseSegmentEntity> segments, String embeddingClaim) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        EmbeddingModel model = embeddingModel();
        List<TextSegment> textSegments = segments.stream()
            .map(segment -> toTextSegment(segment, embeddingClaim))
            .toList();
        List<String> embeddingIds = new ArrayList<>(segments.size());
        for (int from = 0; from < textSegments.size(); from += EmbeddingBatchPolicy.MAX_BATCH_SIZE) {
            int to = Math.min(from + EmbeddingBatchPolicy.MAX_BATCH_SIZE, textSegments.size());
            List<TextSegment> batch = textSegments.subList(from, to);
            List<String> batchIds = segments.subList(from, to).stream()
                .map(this::stableEmbeddingId)
                .toList();
            Response<List<Embedding>> embeddingResponse = model.embedAll(batch);
            List<Embedding> content = embeddingResponse == null ? null : embeddingResponse.content();
            if (content == null || content.size() != batch.size()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "Embedding 返回数量与请求分段数量不一致");
            }
            content.forEach(this::validateEmbeddingDimension);
            // 使用 MySQL segment 主键生成稳定 ES ID。若 ES 写入成功后进程在 DB 回写前宕机，
            // 补偿重试会覆盖同一文档，而不是再生成一份随机 ID 的孤儿向量。
            embeddingStore.addAll(batchIds, content, batch);
            embeddingIds.addAll(batchIds);
        }
        if (embeddingIds.size() != segments.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量存储失败，向量数量(" + embeddingIds.size() + ")与分段数量(" + segments.size() + ")不一致");
        }
        log.info("批量向量化完成: count={}", segments.size());
        return embeddingIds;
    }

    @Override
    public String embedAndStore(KnowledgeBaseSegmentEntity segment) {
        if (segment == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分段不能为空");
        }
        TextSegment textSegment = toTextSegment(segment);
        Response<Embedding> embeddingResponse = embeddingModel().embed(textSegment.text());
        Embedding embedding = embeddingResponse == null ? null : embeddingResponse.content();
        if (embedding == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "Embedding 返回结果为空");
        }
        validateEmbeddingDimension(embedding);
        String embeddingId = stableEmbeddingId(segment);
        embeddingStore.addAll(List.of(embeddingId), List.of(embedding), List.of(textSegment));
        log.info("单条向量化完成: segmentId={}, embeddingId={}", segment.getId(), embeddingId);
        return embeddingId;
    }

    private String stableEmbeddingId(KnowledgeBaseSegmentEntity segment) {
        if (segment.getId() == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "分段尚未落库，无法生成稳定向量 ID");
        }
        return EMBEDDING_ID_PREFIX + segment.getId();
    }

    private void validateEmbeddingDimension(Embedding embedding) {
        int expected = elasticSearchProperties.getDimensions();
        if (expected > 0 && embedding.dimension() != expected) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "Embedding 维度与 ES 索引不一致: actual=" + embedding.dimension()
                    + ", expected=" + expected + "。切换 Embedding 模型后必须同步重建索引");
        }
    }

    @Override
    public void remove(String embeddingId) {
        if (embeddingId == null) {
            return;
        }
        try {
            embeddingStore.remove(embeddingId);
            log.info("删除向量成功: embeddingId={}", embeddingId);
        } catch (Exception e) {
            log.warn("删除向量失败: embeddingId={}, error={}", embeddingId, e.getMessage());
        }
    }

    @Override
    public void removeByEmbeddingIds(List<String> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return;
        }
        try {
            embeddingStore.removeAll(embeddingIds);
            log.info("按embeddingIds批量删除向量成功: count={}", embeddingIds.size());
        } catch (Exception e) {
            log.error("按embeddingIds批量删除向量失败: count={}", embeddingIds.size(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED,
                "按 embeddingId 批量删除向量失败", e);
        }
    }

    @Override
    public void removeByEmbeddingClaim(String embeddingClaim) {
        if (embeddingClaim == null || embeddingClaim.isBlank()) {
            return;
        }
        try {
            Filter filter = metadataKey(MetadataKeyConstant.EMBEDDING_CLAIM)
                .isEqualTo(embeddingClaim);
            embeddingStore.removeAll(filter);
            log.info("按向量化租约令牌删除向量成功: claim={}", embeddingClaim);
        } catch (Exception e) {
            log.error("按向量化租约令牌删除向量失败: claim={}", embeddingClaim, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED,
                "按向量化租约令牌删除向量失败", e);
        }
    }

    @Override
    public void removeByDocId(Long docId) {
        try {
            Filter filter = metadataKey(DOC_ID_KEY).isEqualTo(String.valueOf(docId));
            embeddingStore.removeAll(filter);
            log.info("按docId删除向量成功: docId={}", docId);
        } catch (Exception e) {
            log.warn("按docId删除向量失败: docId={}, error={}", docId, e.getMessage());
        }
    }

    @Override
    public void removeByDocIds(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        try {
            Filter filter = metadataKey(DOC_ID_KEY).isIn(
                docIds.stream().map(String::valueOf).toList());
            embeddingStore.removeAll(filter);
            log.info("按docIds批量删除向量成功: count={}", docIds.size());
        } catch (Exception e) {
            log.warn("按docIds批量删除向量失败: count={}, error={}", docIds.size(), e.getMessage());
        }
    }

    @Override
    public void removeByDocIdAndVersion(Long docId, Long versionId) {
        // 删除失败必须阻断调用方的状态推进（否则 ES 残留孤儿向量、检索命中旧内容），
        // 由调用方决定回滚或对账兜底
        try {
            Filter filter = metadataKey(DOC_ID_KEY).isEqualTo(String.valueOf(docId))
                .and(metadataKey(VERSION_KEY).isEqualTo(String.valueOf(versionId)));
            embeddingStore.removeAll(filter);
            log.info("按docId+versionId删除向量成功: docId={}, versionId={}", docId, versionId);
        } catch (Exception e) {
            log.error("按docId+versionId删除向量失败: docId={}, versionId={}", docId, versionId, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED,
                "删除知识库向量失败: docId=" + docId + ", versionId=" + versionId, e);
        }
    }

    @Override
    public TextSegment toTextSegment(KnowledgeBaseSegmentEntity segment) {
        return toTextSegment(segment, null);
    }

    private TextSegment toTextSegment(
        KnowledgeBaseSegmentEntity segment, String embeddingClaim) {
        Map<String, String> metadataMap = parseMetadataMap(segment.getMetadata());
        Metadata metadata = metadataMap != null ? Metadata.from(metadataMap) : new Metadata();
        if (embeddingClaim != null && !embeddingClaim.isBlank()) {
            metadata.put(MetadataKeyConstant.EMBEDDING_CLAIM, embeddingClaim);
        }
        return TextSegment.from(segment.getText(), metadata);
    }

    private Map<String, String> parseMetadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析分段 metadata JSON 失败: segmentId={}, error={}",
                metadataJson, e.getMessage());
            return null;
        }
    }
}
