package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.linrun.interview.modules.knowledgebase.service.SegmentTextCacheService;
import com.linrun.interview.modules.knowledgebase.util.DocumentPermissionUtils;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识库 Elasticsearch 内容检索器（参考业界实现的 ElasticsearchContentRetriever）。
 *
 * <p>实现 LC4j {@link ContentRetriever}，供 {@code DefaultRetrievalAugmentor} 编排。组合
 * {@link ElasticsearchEmbeddingStore} + {@link EmbeddingModel}（阶段5 已有的 bean），做 KNN 向量检索，
 * 把 {@code EmbeddingMatch} 转成带 {@link ContentMetadata#SCORE} 与
 * {@link ContentMetadata#EMBEDDING_ID} 的 {@link Content}，供 Aggregator 融合/rerank。
 *
 * <p>父子/兄弟分段扩展（small-to-big，对齐业界实践）：检索命中小 chunk 后，开启
 * {@code ParentExpand} 时按 {@code parentChunkId} 取父块章节文本拼接、按 {@code brotherChunkId}
 * 取同组兄弟按序拼接成完整段落，给 LLM 更完整上下文。扩展从 segment 表按冗余列批量查（避免 N+1），
 * 受 {@code maxChars/maxSiblings} 截断；扩展不改变命中 chunk 的 score。权限过滤由 docId + accessibleBy 限定。
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
    private final Set<String> knowledgeBaseIdSet;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeBaseQueryProperties.ParentExpand parentExpand;
    private final KnowledgeBaseQueryProperties.Hybrid hybrid;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;
    private final RestClient restClient;
    private final String indexName;
    private final ObjectMapper objectMapper;
    private final String forcedSearchMode;
    private final Long accessibleUserId;
    private final SegmentTextCacheService segmentTextCache;
    /** 检索进度只发一次（DefaultRetrievalAugmentor 可能对多 query 多次调用 retrieve）。 */
    private final AtomicBoolean retrieveProgressSent = new AtomicBoolean(false);

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand) {
        this(embeddingStore, embeddingModel, maxResults, minScore, knowledgeBaseIds,
            segmentService, parentExpand, new KnowledgeBaseQueryProperties.Hybrid(), null, null);
    }

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand,
                                                   Consumer<String> progressCallback) {
        this(embeddingStore, embeddingModel, maxResults, minScore, knowledgeBaseIds,
            segmentService, parentExpand, new KnowledgeBaseQueryProperties.Hybrid(), progressCallback, null);
    }

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand,
                                                   KnowledgeBaseQueryProperties.Hybrid hybrid,
                                                   Consumer<String> progressCallback) {
        this(embeddingStore, embeddingModel, maxResults, minScore, knowledgeBaseIds,
            segmentService, parentExpand, hybrid, progressCallback, null);
    }

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand,
                                                   KnowledgeBaseQueryProperties.Hybrid hybrid,
                                                   Consumer<String> progressCallback,
                                                   RagQueryTrace trace) {
        this(embeddingStore, embeddingModel, maxResults, minScore, knowledgeBaseIds, segmentService,
            parentExpand, hybrid, progressCallback, trace, null, null, null);
    }

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand,
                                                   KnowledgeBaseQueryProperties.Hybrid hybrid,
                                                   Consumer<String> progressCallback,
                                                   RagQueryTrace trace,
                                                   RestClient restClient,
                                                   String indexName,
                                                   ObjectMapper objectMapper) {
        this(embeddingStore, embeddingModel, maxResults, minScore, knowledgeBaseIds, segmentService,
            parentExpand, hybrid, progressCallback, trace, restClient, indexName, objectMapper,
            null, null, null);
    }

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeSegmentService segmentService,
                                                   KnowledgeBaseQueryProperties.ParentExpand parentExpand,
                                                   KnowledgeBaseQueryProperties.Hybrid hybrid,
                                                   Consumer<String> progressCallback,
                                                   RagQueryTrace trace,
                                                   RestClient restClient,
                                                   String indexName,
                                                   ObjectMapper objectMapper,
                                                   String forcedSearchMode,
                                                   Long accessibleUserId,
                                                   SegmentTextCacheService segmentTextCache) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = buildKbFilter(knowledgeBaseIds, accessibleUserId);
        this.knowledgeBaseIdSet = knowledgeBaseIds == null ? Set.of() : knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toUnmodifiableSet());
        this.segmentService = segmentService;
        this.parentExpand = parentExpand;
        this.hybrid = hybrid;
        this.progressCallback = progressCallback;
        this.trace = trace;
        this.restClient = restClient;
        this.indexName = indexName;
        this.objectMapper = objectMapper;
        this.forcedSearchMode = forcedSearchMode;
        this.accessibleUserId = accessibleUserId;
        this.segmentTextCache = segmentTextCache;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (progressCallback != null && retrieveProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在检索知识库...");
        }
        EmbeddingSearchResult<TextSegment> result;
        if (isFullTextMode()) {
            result = search(null, query.text());
        } else {
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
            result = search(builder.build(), query.text());
        }

        List<Content> contents = result.matches().stream()
            .map(this::toContent)
            .filter(this::matchesKnowledgeBaseFilter)
            .collect(Collectors.toList());

        List<Content> expanded = parentExpand != null && parentExpand.isEnabled()
            ? expandWithContext(contents)
            : contents;
        if (trace != null) {
            trace.recordRetrieved(expanded);
        }
        log.info("[InterviewElasticsearchContentRetriever] 检索完成: query='{}', 命中 {} 条, 扩展后 {} 条",
            query.text(), contents.size(), expanded.size());
        return expanded;
    }

    private EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, String queryText) {
        String mode = searchMode();
        try {
            return switch (mode) {
                case "vector" -> vectorSearch(request);
                case "full_text" -> fullTextSearch(queryText);
                default -> hybrid.isEnabled()
                    ? localHybridSearch(request, queryText)
                    : embeddingStore.search(request);
            };
        } catch (Exception e) {
            log.warn("[InterviewElasticsearchContentRetriever] {} 检索失败，降级全文检索: {}",
                mode, e.getMessage(), e);
            try {
                return fullTextSearch(queryText);
            } catch (Exception fallback) {
                log.warn("[InterviewElasticsearchContentRetriever] 全文检索失败，返回空结果: {}",
                    fallback.getMessage(), fallback);
                return new EmbeddingSearchResult<>(List.of());
            }
        }
    }

    private EmbeddingSearchResult<TextSegment> localHybridSearch(EmbeddingSearchRequest request,
        String queryText) {
        List<InterviewDefaultContent> vector = vectorSearch(request).matches().stream()
            .map(this::toDefaultContent)
            .toList();
        List<InterviewDefaultContent> fullText = fullTextSearch(queryText).matches().stream()
            .map(this::toDefaultContent)
            .toList();
        List<Content> fused = InterviewReciprocalRankFuser.fuse(
            List.of(vector, fullText), hybrid.getRrfK());

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, fused.size()); i++) {
            Content content = fused.get(i);
            String embeddingId = content.textSegment().metadata()
                .getString(MetadataKeyConstant.EMBEDDING_ID);
            matches.add(new EmbeddingMatch<>(
                1.0 / (i + 1), embeddingId, null, content.textSegment()));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    private EmbeddingSearchResult<TextSegment> fullTextSearch(String queryText) {
        if (restClient != null && objectMapper != null && indexName != null && !indexName.isBlank()) {
            try {
                return filteredFullTextSearch(queryText);
            } catch (Exception e) {
                log.warn("[InterviewElasticsearchContentRetriever] 带过滤全文检索失败，退回默认全文检索: {}",
                    e.getMessage(), e);
            }
        }
        return langChainFullTextSearch(queryText);
    }

    private EmbeddingSearchResult<TextSegment> vectorSearch(EmbeddingSearchRequest searchRequest) {
        if (restClient != null && objectMapper != null && indexName != null && !indexName.isBlank()) {
            try {
                return elasticsearchVectorSearch(searchRequest);
            } catch (Exception e) {
                log.warn("[InterviewElasticsearchContentRetriever] 原生向量检索失败，退回默认向量检索: {}",
                    e.getMessage(), e);
            }
        }
        return embeddingStore.search(searchRequest);
    }

    private EmbeddingSearchResult<TextSegment> elasticsearchVectorSearch(
        EmbeddingSearchRequest searchRequest) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", searchRequest.maxResults());
        root.putArray("_source").add("text").add("metadata");
        ObjectNode knn = root.putObject("knn");
        knn.put("field", "vector");
        ArrayNode vector = knn.putArray("query_vector");
        for (float value : searchRequest.queryEmbedding().vector()) {
            vector.add(value);
        }
        knn.put("k", searchRequest.maxResults());
        knn.put("num_candidates", Math.max(searchRequest.maxResults() * 10, 100));
        ObjectNode metadataFilter = buildNativeMetadataFilter();
        if (metadataFilter != null) {
            knn.set("filter", metadataFilter);
        }

        Request request = new Request("POST", "/" + indexName + "/_search");
        request.setJsonEntity(objectMapper.writeValueAsString(root));
        return parseSearchResponse(request, searchRequest.minScore());
    }

    private EmbeddingSearchResult<TextSegment> langChainFullTextSearch(String queryText) {
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.fullTextSearch(queryText).stream()
            .limit(Math.max(maxResults, 1))
            .map(segment -> new EmbeddingMatch<>(1.0, null, null, segment))
            .filter(match -> matchesKnowledgeBaseFilter(toContent(match)))
            .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    private EmbeddingSearchResult<TextSegment> filteredFullTextSearch(String queryText) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", Math.max(maxResults, 1));
        root.putArray("_source").add("text").add("metadata");
        ObjectNode bool = root.putObject("query").putObject("bool");
        bool.putArray("must").addObject().putObject("match").putObject("text").put("query", queryText);
        ObjectNode metadataFilter = buildNativeMetadataFilter();
        if (metadataFilter != null) {
            bool.putArray("filter").add(metadataFilter);
        }

        Request request = new Request("POST", "/" + indexName + "/_search");
        request.setJsonEntity(objectMapper.writeValueAsString(root));
        return parseSearchResponse(request, 0.0);
    }

    private EmbeddingSearchResult<TextSegment> parseSearchResponse(Request request, double minScore)
        throws Exception {
        try (InputStream content = restClient.performRequest(request).getEntity().getContent()) {
            JsonNode hits = objectMapper.readTree(content).path("hits").path("hits");
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (JsonNode hit : hits) {
                double score = hit.path("_score").asDouble(1.0);
                if (minScore > 0 && score < minScore) {
                    continue;
                }
                JsonNode source = hit.path("_source");
                String text = source.path("text").asText("");
                if (text.isBlank()) {
                    continue;
                }
                Map<String, Object> metadataMap = objectMapper.convertValue(source.path("metadata"),
                    new TypeReference<Map<String, Object>>() {});
                TextSegment segment = new TextSegment(text, Metadata.from(metadataMap));
                matches.add(new EmbeddingMatch<>(score, hit.path("_id").asText(null), null, segment));
            }
            return new EmbeddingSearchResult<>(matches);
        }
    }

    private boolean isFullTextMode() {
        return "full_text".equals(searchMode());
    }

    private String searchMode() {
        if (forcedSearchMode != null && !forcedSearchMode.isBlank()) {
            return forcedSearchMode;
        }
        return hybrid != null && hybrid.getMode() != null ? hybrid.getMode() : "hybrid";
    }

    private boolean matchesKnowledgeBaseFilter(Content content) {
        Metadata metadata = content.textSegment().metadata();
        if (accessibleUserId != null) {
            String accessibleBy = metadata.getString(MetadataKeyConstant.ACCESSIBLE_BY);
            if (!DocumentPermissionUtils.canAccess(accessibleBy, accessibleUserId)) {
                return false;
            }
        }
        String expireRaw = metadata.getString(MetadataKeyConstant.EXPIRE_DATE);
        if (expireRaw != null && !expireRaw.isBlank()) {
            try {
                if (LocalDate.parse(expireRaw).isBefore(LocalDate.now())) {
                    return false;
                }
            } catch (Exception ignored) {
                // 非法日期格式不过滤
            }
        }
        if (knowledgeBaseIdSet.isEmpty()) {
            return true;
        }
        String docId = metadata.getString(MetadataKeyConstant.DOC_ID);
        return knowledgeBaseIdSet.contains(docId);
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
            Map<String, String> loaded = loadParentTexts(parentChunkIds);
            parentTextByChunkId.putAll(loaded);
        }
        Map<String, List<KnowledgeBaseSegmentEntity>> brothersByGroupId = new LinkedHashMap<>();
        if (!brotherChunkIds.isEmpty()) {
            List<KnowledgeBaseSegmentEntity> brothers =
                segmentService.findByBrotherChunkIdIn(new ArrayList<>(brotherChunkIds));
            if (segmentTextCache != null) {
                segmentTextCache.warmChunkTexts(brothers);
            }
            for (KnowledgeBaseSegmentEntity s : brothers) {
                brothersByGroupId.computeIfAbsent(s.getBrotherChunkId(), k -> new ArrayList<>()).add(s);
            }
        }

        // 3. 逐条命中 chunk 做扩展
        int maxChars = parentExpand.getMaxChars() > 0 ? parentExpand.getMaxChars() : Integer.MAX_VALUE;
        int maxSiblings = parentExpand.getMaxSiblings() > 0 ? parentExpand.getMaxSiblings() : Integer.MAX_VALUE;
        List<Content> expanded = new ArrayList<>(hits.size());
        Set<String> seenTexts = new HashSet<>();
        for (Content hit : hits) {
            TextSegment seg = hit.textSegment();
            Metadata meta = seg.metadata();
            String expandedText = ParentExpandHelper.buildExpandedText(
                seg.text(), meta, parentTextByChunkId, brothersByGroupId, maxChars, maxSiblings,
                parentExpand.getStrategy());
            Content expandedContent;
            if (expandedText.equals(seg.text())) {
                expandedContent = hit;
            } else {
                // meta 已含 SCORE/EMBEDDING_ID（toContent 写入），仅追加 expanded 标记
                Metadata enriched = meta.put("expanded", "1");
                TextSegment expandedSeg = new TextSegment(expandedText, enriched);
                expandedContent = Content.from(expandedSeg, hit.metadata());
            }
            if (seenTexts.add(expandedContent.textSegment().text())) {
                expanded.add(expandedContent);
            }
        }
        return expanded;
    }

    private Map<String, String> loadParentTexts(Set<String> parentChunkIds) {
        Map<String, String> loaded = new HashMap<>();
        List<KnowledgeBaseSegmentEntity> segments =
            segmentService.findByChunkIdIn(new ArrayList<>(parentChunkIds));
        for (KnowledgeBaseSegmentEntity segment : segments) {
            if (segment.getText() == null || segment.getText().isBlank()) {
                continue;
            }
            String text = segmentTextCache == null
                ? segment.getText()
                : segmentTextCache.getTextByChunkId(segment.getChunkId(), segment::getText);
            if (text != null && !text.isBlank()) {
                loaded.put(segment.getChunkId(), text);
            }
        }
        return loaded;
    }

    private ObjectNode buildNativeMetadataFilter() {
        if (objectMapper == null) {
            return null;
        }
        ArrayNode must = objectMapper.createArrayNode();
        if (accessibleUserId != null) {
            ObjectNode accessBool = objectMapper.createObjectNode();
            ObjectNode innerBool = accessBool.putObject("bool");
            ArrayNode should = objectMapper.createArrayNode();
            should.addObject().putObject("term")
                .putObject("metadata." + MetadataKeyConstant.ACCESSIBLE_BY + ".keyword")
                .put("value", String.valueOf(accessibleUserId));
            should.addObject().putObject("term")
                .putObject("metadata." + MetadataKeyConstant.ACCESSIBLE_BY + ".keyword")
                .put("value", DocumentPermissionUtils.PUBLIC_TOKEN);
            innerBool.set("should", should);
            innerBool.put("minimum_should_match", 1);
            must.add(accessBool);
        }
        if (!knowledgeBaseIdSet.isEmpty()) {
            ArrayNode terms = must.addObject().putObject("terms")
                .putArray("metadata." + MetadataKeyConstant.DOC_ID + ".keyword");
            knowledgeBaseIdSet.forEach(terms::add);
        }
        if (must.isEmpty()) {
            return null;
        }
        ObjectNode bool = objectMapper.createObjectNode();
        bool.set("must", must);
        ObjectNode filter = objectMapper.createObjectNode();
        filter.set("bool", bool);
        return filter;
    }

    private Content toContent(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();
        // 检索器写入 SCORE/EMBEDDING_ID，供 Aggregator 融合/rerank 与 DefaultContent 去重使用
        Metadata enriched = metadata.copy().put(ContentMetadata.SCORE.name(), match.score());
        if (match.embeddingId() != null && !match.embeddingId().isBlank()) {
            enriched.put(MetadataKeyConstant.EMBEDDING_ID, match.embeddingId());
        }
        TextSegment scored = new TextSegment(segment.text(), enriched);
        return Content.from(scored, Map.of(
            ContentMetadata.SCORE, match.score(),
            ContentMetadata.EMBEDDING_ID, match.embeddingId() != null ? match.embeddingId() : ""));
    }

    private InterviewDefaultContent toDefaultContent(EmbeddingMatch<TextSegment> match) {
        Content content = toContent(match);
        return new InterviewDefaultContent(content.textSegment(), content.metadata());
    }

    /**
     * 构建 docId + accessibleBy metadata filter。
     */
    private Filter buildKbFilter(List<Long> knowledgeBaseIds, Long userId) {
        Filter accessFilter = userId != null
            ? metadataKey(MetadataKeyConstant.ACCESSIBLE_BY).isEqualTo(String.valueOf(userId))
            : null;
        Filter docFilter = buildDocIdFilter(knowledgeBaseIds);
        if (accessFilter == null) {
            return docFilter;
        }
        if (docFilter == null) {
            return accessFilter;
        }
        return accessFilter.and(docFilter);
    }

    private Filter buildDocIdFilter(List<Long> knowledgeBaseIds) {
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
