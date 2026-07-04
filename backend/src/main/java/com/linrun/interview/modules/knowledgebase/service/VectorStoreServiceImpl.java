package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {

    private static final String DOC_ID_KEY = "docId";
    private static final String VERSION_KEY = "version";
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final ElasticsearchEmbeddingStore embeddingStore;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;

    private EmbeddingModel embeddingModel() {
        return llmProviderRegistry.getDefaultEmbeddingModel();
    }

    @Override
    public List<String> embedAndStore(List<KnowledgeBaseSegmentEntity> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        EmbeddingModel model = embeddingModel();
        List<TextSegment> textSegments = segments.stream().map(this::toTextSegment).toList();
        List<String> embeddingIds = new ArrayList<>(segments.size());
        for (int from = 0; from < textSegments.size(); from += EMBEDDING_BATCH_SIZE) {
            int to = Math.min(from + EMBEDDING_BATCH_SIZE, textSegments.size());
            List<TextSegment> batch = textSegments.subList(from, to);
            Response<List<Embedding>> embeddingResponse = model.embedAll(batch);
            embeddingIds.addAll(embeddingStore.addAll(embeddingResponse.content(), batch));
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
        String embeddingId = embeddingStore.add(embeddingResponse.content(), textSegment);
        log.info("单条向量化完成: segmentId={}, embeddingId={}", segment.getId(), embeddingId);
        return embeddingId;
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
    public void removeByDocId(Long docId) {
        try {
            Filter filter = metadataKey(DOC_ID_KEY).isEqualTo(docId);
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
            Filter filter = metadataKey(DOC_ID_KEY).isIn(docIds);
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
            Filter filter = metadataKey(DOC_ID_KEY).isEqualTo(docId)
                .and(metadataKey(VERSION_KEY).isEqualTo(versionId));
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
        Map<String, String> metadataMap = parseMetadataMap(segment.getMetadata());
        Metadata metadata = metadataMap != null ? Metadata.from(metadataMap) : new Metadata();
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
