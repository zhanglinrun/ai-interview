package interview.guide.modules.knowledgebase.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.constant.MetadataKeyConstant;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识库向量存储服务（LangChain4j + Elasticsearch 版，对齐 know-engine VectorStoreServiceImpl）。
 *
 * <p>简化版：全量写入 + ES 原生相似度检索 + 按 kb_id 删除。替代原 Spring AI PgVectorStore +
 * VectorRepository 的复杂体系（增量 diff/分块并行/信号量限流/small-to-big/RRF 混合检索/user_id 防护），
 * 对齐 know-engine 的简洁实现。保留向量化状态机（PROCESSING/COMPLETED/FAILED）与 chunkCount，
 * 供前端列表展示与索引状态轮询。
 *
 * <p>embedding model 通过 {@link LlmProviderRegistry#getDefaultEmbeddingModel()} 获取，
 * 支持多 Provider 路由（know-engine 单 Provider 直 new，本项目保留多 Provider 能力）。
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    /**
     * 阿里云 DashScope Embedding API 批量大小限制。
     */
    private static final int MAX_BATCH_SIZE = 10;

    private final ElasticsearchEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeBaseChunkingService chunkingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseVectorService(ElasticsearchEmbeddingStore embeddingStore,
                                      LlmProviderRegistry llmProviderRegistry,
                                      KnowledgeBaseChunkingService chunkingService,
                                      KnowledgeBaseRepository knowledgeBaseRepository) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = llmProviderRegistry.getDefaultEmbeddingModel();
        this.chunkingService = chunkingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 将知识库内容向量化并存储到 Elasticsearch。
     * <p>注意：此方法不加事务，避免外部 API 调用占用 DB 连接。
     *
     * @param knowledgeBaseId 知识库ID
     * @param content         知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, content.length());
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
        finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.PROCESSING, null, null);
        try {
            KnowledgeBaseChunkingService.ChunkingResult chunkingResult = chunkingService.splitWithQuality(content);
            List<TextSegment> chunks = chunkingResult.chunks();
            log.info("文本分块完成: chunks={}, rawChunks={}, filtered={}, documentLength={}, qualityScore={}",
                chunks.size(), chunkingResult.rawChunkCount(), chunkingResult.filteredChunkCount(),
                chunkingResult.documentLength(), chunkingResult.qualityScore());

            enrichChunkMetadata(chunks, knowledgeBaseId, knowledgeBase);

            if (chunks.isEmpty()) {
                log.warn("有效 chunk 为空，跳过向量化: kbId={}", knowledgeBaseId);
                finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.COMPLETED, null, 0);
                return;
            }

            // 全量写入：先清旧向量，再批量 embedding 入库
            deleteByKnowledgeBaseId(knowledgeBaseId);
            embedInBatches(chunks);

            log.info("知识库向量化完成: kbId={}, chunks={}", knowledgeBaseId, chunks.size());
            finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.COMPLETED, null, chunks.size());
        } catch (Exception e) {
            finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.FAILED, e.getMessage(), 0);
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为每个 chunk 填充来源/检索 metadata：docId、文件名、分类、父子兄弟关系由 splitter 已写入。
     */
    private void enrichChunkMetadata(List<TextSegment> chunks, Long knowledgeBaseId,
                                     KnowledgeBaseEntity knowledgeBase) {
        for (int i = 0; i < chunks.size(); i++) {
            TextSegment chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.metadata().toMap());
            metadata.put(MetadataKeyConstant.DOC_ID, knowledgeBaseId.toString());
            if (knowledgeBase != null) {
                putIfNotBlank(metadata, MetadataKeyConstant.FILE_NAME, knowledgeBase.getOriginalFilename());
                putIfNotBlank(metadata, MetadataKeyConstant.CATEGORY, knowledgeBase.getCategory());
                if (knowledgeBase.getUserId() != null) {
                    metadata.put(MetadataKeyConstant.ACCESSIBLE_BY, knowledgeBase.getUserId().toString());
                }
            }
            // TextSegment 的 metadata 不可变，重建带新 metadata 的 segment
            chunks.set(i, new TextSegment(chunk.text(),
                dev.langchain4j.data.document.Metadata.from(metadata)));
        }
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    /**
     * 分批向量化并入库：每批 MAX_BATCH_SIZE 个 chunk，embedding 后批量写入 ES。
     */
    private void embedInBatches(List<TextSegment> chunks) {
        int totalChunks = chunks.size();
        int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        log.info("开始分批向量化: 总共 {} 个 chunks，分 {} 批处理，每批最多 {} 个",
            totalChunks, batchCount, MAX_BATCH_SIZE);

        for (int i = 0; i < batchCount; i++) {
            int start = i * MAX_BATCH_SIZE;
            int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
            List<TextSegment> batch = chunks.subList(start, end);
            addBatch(batch);
        }
    }

    private void addBatch(List<TextSegment> batch) {
        var embeddingResponse = embeddingModel.embedAll(batch);
        List<Embedding> embeddings = embeddingResponse.content();
        List<String> ids = embeddingStore.addAll(embeddings, batch);
        log.info("批次写入 ES 完成: count={}", ids.size());
    }

    /**
     * 基于多个知识库进行相似度搜索（纯向量通道，ES 原生）。
     *
     * @param query           查询文本
     * @param knowledgeBaseIds 知识库ID列表（为空则搜索所有）
     * @param topK            返回 top K 个结果
     * @param minScore        最低相似度
     * @return 相关 TextSegment 列表
     */
    public List<TextSegment> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(topK, 1));
            if (minScore > 0) {
                builder.minScore(minScore);
            }
            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filter(buildKbFilter(knowledgeBaseIds));
            }

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(builder.build());
            List<TextSegment> segments = result.matches().stream()
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());
            log.info("搜索完成: 找到 {} 个相关文档", segments.size());
            return segments;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 ES metadata filter：kb_id 限定（任一命中）。
     * <p>单 kb_id 用 isEqualTo；多 kb_id 用 Filter.or 链式组合。
     */
    private Filter buildKbFilter(List<Long> knowledgeBaseIds) {
        List<String> idStrings = knowledgeBaseIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toList());
        List<Filter> filters = idStrings.stream()
            .map(id -> metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(id))
            .collect(Collectors.toList());
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return filters.stream().reduce((a, b) -> a.or(b))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "knowledgeBaseIds 不能为空"));
    }

    /**
     * 收尾向量化状态：写入状态机（PROCESSING / COMPLETED / FAILED），可选更新 chunkCount 与错误信息。
     */
    private void finalizeVectorization(KnowledgeBaseEntity knowledgeBase, Long knowledgeBaseId,
                                       VectorStatus status, String error, Integer chunkCount) {
        try {
            KnowledgeBaseEntity target = knowledgeBase != null
                ? knowledgeBase
                : knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
            if (target == null) {
                return;
            }
            target.setVectorStatus(status);
            if (chunkCount != null) {
                target.setChunkCount(chunkCount);
            }
            if (status == VectorStatus.COMPLETED) {
                target.setVectorError(null);
            } else if (error != null) {
                target.setVectorError(truncateError(error));
            }
            knowledgeBaseRepository.save(target);
        } catch (Exception e) {
            log.warn("更新向量化状态失败: kbId={}, status={}, error={}",
                knowledgeBaseId, status, e.getMessage());
        }
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 490 ? error.substring(0, 490) : error;
    }

    /**
     * 删除指定知识库的所有向量数据（ES metadata filter）。
     *
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(knowledgeBaseId.toString());
            embeddingStore.removeAll(filter);
            log.info("按 kbId 删除向量成功: kbId={}", knowledgeBaseId);
        } catch (Exception e) {
            log.warn("按 kbId 删除向量失败: kbId={}, error={}", knowledgeBaseId, e.getMessage());
        }
    }
}
