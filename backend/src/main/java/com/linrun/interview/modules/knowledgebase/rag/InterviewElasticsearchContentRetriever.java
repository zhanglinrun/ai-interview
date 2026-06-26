package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeSegmentService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识库 Elasticsearch 内容检索器（移植自 know-engine 的 KnowEngineElasticsearchContentRetriever）。
 *
 * <p>实现 LC4j {@link ContentRetriever}，供 {@code DefaultRetrievalAugmentor} 编排。组合
 * {@link ElasticsearchEmbeddingStore} + {@link EmbeddingModel}（阶段5 已有的 bean），做 KNN 向量检索，
 * 把 {@code EmbeddingMatch} 转成带 {@link ContentMetadata#SCORE} 与
 * {@link ContentMetadata#EMBEDDING_ID} 的 {@link Content}，供 Aggregator 融合/rerank。
 *
 * <p>父子/兄弟分段扩展（small-to-big，对齐 know-engine）：检索命中小 chunk 后，开启
 * {@code ParentExpand} 时按 {@code parentChunkId} 取父块章节文本拼接、按 {@code brotherChunkId}
 * 取同组兄弟按序拼接成完整段落，给 LLM 更完整上下文。扩展从 segment 表按冗余列批量查（避免 N+1），
 * 受 {@code maxChars/maxSiblings} 截断；扩展不改变命中 chunk 的 score。权限过滤由 docId filter 限定。
 *
 * <p>每次对话按 knowledgeBaseIds 构建 filter，故为 prototype 作用域，由调用方 new 或工厂创建。
 */
@Slf4j
public class InterviewElasticsearchContentRetriever implements ContentRetriever {

    private final ElasticsearchEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeBaseQueryProperties.ParentExpand parentExpand;

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = buildKbFilter(knowledgeBaseIds);
        this.segmentService = segmentService;
        this.parentExpand = parentExpand;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
        var builder = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(Math.max(maxResults, 1));
        if (minScore > 0) {
            builder.minScore(minScore);
        }
        if (filter != null) {
            builder.filter(filter);
        }

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(builder.build());
        List<Content> contents = result.matches().stream()
            .map(this::toContent)
            .collect(Collectors.toList());

        List<Content> expanded = parentExpand != null && parentExpand.isEnabled()
            ? expandWithContext(contents)
            : contents;
        log.info("[InterviewElasticsearchContentRetriever] 检索完成: query='{}', 命中 {} 条, 扩展后 {} 条",
            query.text(), contents.size(), expanded.size());
        return expanded;
    }

    /**
     * 父子/兄弟扩展：对命中 chunk 批量取父块与同组兄弟，拼接成更完整上下文。
     * 扩展后 Content 的 score 沿用命中 chunk 的相关性分（代表命中精度，不因拼接而改变）。
     */
    private List<Content> expandWithContext(List<Content> hits) {
        if (hits.isEmpty() || segmentService == null) {
            return hits;
        }
        // 1. 收集需要补全的 parentChunkId / brotherChunkId
        Set<String> parentChunkIds = new HashSet<>();
        Set<String> brotherChunkIds = new HashSet<>();
        for (Content c : hits) {
            Metadata meta = c.textSegment().metadata();
            String pid = meta.getString(MetadataKeyConstant.PARENT_CHUNK_ID);
            String bid = meta.getString(MetadataKeyConstant.BROTHER_CHUNK_ID);
            if (pid != null && !pid.isBlank()) {
                parentChunkIds.add(pid);
            }
            if (bid != null && !bid.isBlank()) {
                brotherChunkIds.add(bid);
            }
        }

        // 2. 批量查父块（按 chunkId）与同组兄弟（按 brotherChunkId，已按 index 排序）
        Map<String, String> parentTextByChunkId = new HashMap<>();
        if (!parentChunkIds.isEmpty()) {
            for (KnowledgeBaseSegmentEntity s : segmentService.findByChunkIdIn(new ArrayList<>(parentChunkIds))) {
                if (s.getText() != null && !s.getText().isBlank()) {
                    parentTextByChunkId.put(s.getChunkId(), s.getText());
                }
            }
        }
        Map<String, List<KnowledgeBaseSegmentEntity>> brothersByGroupId = new LinkedHashMap<>();
        if (!brotherChunkIds.isEmpty()) {
            for (KnowledgeBaseSegmentEntity s : segmentService.findByBrotherChunkIdIn(new ArrayList<>(brotherChunkIds))) {
                brothersByGroupId.computeIfAbsent(s.getBrotherChunkId(), k -> new ArrayList<>()).add(s);
            }
        }

        // 3. 逐条命中 chunk 做扩展
        int maxChars = parentExpand.getMaxChars() > 0 ? parentExpand.getMaxChars() : Integer.MAX_VALUE;
        int maxSiblings = parentExpand.getMaxSiblings() > 0 ? parentExpand.getMaxSiblings() : Integer.MAX_VALUE;
        List<Content> expanded = new ArrayList<>(hits.size());
        for (Content hit : hits) {
            TextSegment seg = hit.textSegment();
            Metadata meta = seg.metadata();
            String expandedText = buildExpandedText(
                seg.text(), meta, parentTextByChunkId, brothersByGroupId, maxChars, maxSiblings);
            if (expandedText.equals(seg.text())) {
                expanded.add(hit);
            } else {
                // meta 已含 SCORE/EMBEDDING_ID（toContent 写入），仅追加 expanded 标记
                Metadata enriched = meta.put("expanded", "1");
                TextSegment expandedSeg = new TextSegment(expandedText, enriched);
                expanded.add(Content.from(expandedSeg, hit.metadata()));
            }
        }
        return expanded;
    }

    /**
     * 拼接扩展文本：父块章节文本 + 同组兄弟按序拼接。命中 chunk 自身必包含在内。
     * 受 maxChars 截断、maxSiblings 限制兄弟数。
     */
    private String buildExpandedText(String hitText, Metadata meta,
                                     Map<String, String> parentTextByChunkId,
                                     Map<String, List<KnowledgeBaseSegmentEntity>> brothersByGroupId,
                                     int maxChars, int maxSiblings) {
        StringBuilder sb = new StringBuilder();

        // 父块在前（更高级标题章节，提供骨架上下文）
        String pid = meta.getString(MetadataKeyConstant.PARENT_CHUNK_ID);
        if (pid != null) {
            String parentText = parentTextByChunkId.get(pid);
            if (parentText != null && !parentText.isBlank()) {
                appendTruncated(sb, parentText, maxChars);
            }
        }

        // 同组兄弟按序拼接（含命中 chunk 自身）；若命中 chunk 不在兄弟组（未超长切片），单用 hitText
        String bid = meta.getString(MetadataKeyConstant.BROTHER_CHUNK_ID);
        List<KnowledgeBaseSegmentEntity> brothers = bid != null ? brothersByGroupId.get(bid) : null;
        if (brothers != null && brothers.size() > 1) {
            int added = 0;
            for (KnowledgeBaseSegmentEntity b : brothers) {
                if (added >= maxSiblings) {
                    break;
                }
                if (b.getText() != null && !b.getText().isBlank()) {
                    appendTruncated(sb, b.getText(), maxChars);
                    added++;
                }
            }
        } else if (sb.length() == 0) {
            // 无父无兄弟：直接返回原文本，避免无谓拷贝
            return hitText;
        } else {
            appendTruncated(sb, hitText, maxChars);
        }
        return sb.toString();
    }

    private void appendTruncated(StringBuilder sb, String text, int maxChars) {
        if (sb.length() >= maxChars) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        int remaining = maxChars - sb.length();
        sb.append(text, 0, Math.min(text.length(), remaining));
    }

    private Content toContent(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();
        // 检索器写入 SCORE/EMBEDDING_ID，供 Aggregator 融合/rerank 与 DefaultContent 去重使用
        Metadata enriched = metadata.put(ContentMetadata.SCORE.name(), match.score())
            .put(MetadataKeyConstant.EMBEDDING_ID, match.embeddingId());
        TextSegment scored = new TextSegment(segment.text(), enriched);
        return Content.from(scored, Map.of(
            ContentMetadata.SCORE, match.score(),
            ContentMetadata.EMBEDDING_ID, match.embeddingId()));
    }

    /**
     * 构建 docId metadata filter（任一知识库命中）。
     */
    private Filter buildKbFilter(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return null;
        }
        List<Filter> filters = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(id -> metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(String.valueOf(id)))
            .toList();
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return filters.stream().reduce((a, b) -> a.or(b))
            .orElse(null);
    }
}
