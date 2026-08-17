package com.linrun.interview.rag.service;
import com.linrun.interview.rag.service.InterviewReciprocalRankFuser;
import com.linrun.interview.rag.model.InterviewDefaultContent;
import com.linrun.interview.rag.model.RagQueryTrace;
import com.linrun.interview.rag.service.ContextExpansionService;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceScope;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import com.linrun.interview.rag.service.ElasticsearchRetrieverPort;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.document.util.DocumentPermissionUtils;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.ResponseException;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * <p>本类只返回小块召回结果。父子/兄弟 small-to-big 扩展由内容聚合器在 RRF/rerank 完成后执行，
 * 防止较大的父块正文提前参与精排、稀释命中子块的相关性。
 *
 * <p>每次对话按 knowledgeBaseIds 构建 filter，故为 prototype 作用域，由调用方 new 或工厂创建。
 */
@Slf4j
public class InterviewElasticsearchContentRetriever implements ElasticsearchRetrieverPort {

    private final ElasticsearchEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;
    private final Set<String> knowledgeBaseIdSet;
    private final KnowledgeBaseQueryProperties.Hybrid hybrid;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;
    private final RestClient restClient;
    private final String indexName;
    private final ObjectMapper objectMapper;
    private final String forcedSearchMode;
    private final Long accessibleUserId;
    private final EvidenceScope evidenceScope;
    private final Integer expectedDimensions;
    /** 检索进度只发一次（DefaultRetrievalAugmentor 可能对多 query 多次调用 retrieve）。 */
    private final AtomicBoolean retrieveProgressSent = new AtomicBoolean(false);

    /**
     * 构建只负责召回小块的检索器。EvidenceScope 会同时下推到向量与 BM25 查询，并在结果层
     * 再次校验；父块和兄弟块由后置的 {@link ContextExpansionService} 扩展。
     */
    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds,
                                                   KnowledgeBaseQueryProperties.Hybrid hybrid,
                                                   Consumer<String> progressCallback,
                                                   RagQueryTrace trace,
                                                   RestClient restClient,
                                                   String indexName,
                                                   ObjectMapper objectMapper,
                                                   String forcedSearchMode,
                                                   Long accessibleUserId,
                                                   EvidenceScope evidenceScope,
                                                   Integer expectedDimensions) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = evidenceScope != null
            ? buildEvidenceFilter(evidenceScope)
            : buildKbFilter(knowledgeBaseIds, accessibleUserId);
        this.knowledgeBaseIdSet = knowledgeBaseIds == null ? Set.of() : knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toUnmodifiableSet());
        this.hybrid = hybrid;
        this.progressCallback = progressCallback;
        this.trace = trace;
        this.restClient = restClient;
        this.indexName = indexName;
        this.objectMapper = objectMapper;
        this.forcedSearchMode = forcedSearchMode;
        this.accessibleUserId = accessibleUserId;
        this.evidenceScope = evidenceScope;
        this.expectedDimensions = expectedDimensions;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (progressCallback != null && retrieveProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在召回候选...");
        }
        RagQueryTrace.Span span = RagQueryTrace.start(trace, RagQueryTrace.SPAN_RETRIEVAL, RagQueryTrace.TYPE_RETRIEVER);
        if (span != null) {
            span.input(query == null || query.text() == null ? "" : query.text());
        }
        try {
            return retrieveAndRecord(query, span);
        } catch (RuntimeException e) {
            if (span != null) {
                span.fail(e.getMessage());
            }
            throw e;
        }
    }

    private List<Content> retrieveAndRecord(Query query, RagQueryTrace.Span span) {
        EmbeddingSearchResult<TextSegment> result;
        if (isFullTextMode()) {
            result = search(null, query.text());
        } else {
            Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
            if (queryEmbedding == null) {
                throw new IllegalStateException("查询 Embedding 返回为空");
            }
            if (expectedDimensions != null && expectedDimensions > 0
                && queryEmbedding.dimension() != expectedDimensions) {
                throw new IllegalStateException(
                    "查询 Embedding 维度与 ES 索引不一致: actual=" + queryEmbedding.dimension()
                        + ", expected=" + expectedDimensions);
            }
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

        if (trace != null) {
            trace.recordRetrieved(contents);
        }
        if (span != null) {
            span.complete(contents.size() + " docs");
        }
        log.info("[InterviewElasticsearchContentRetriever] 子块检索完成: query='{}', 命中 {} 条",
            query.text(), contents.size());
        return contents;
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
            if (isMissingIndex(e)) {
                log.debug("[InterviewElasticsearchContentRetriever] 向量索引尚未创建，返回空结果");
                return new EmbeddingSearchResult<>(List.of());
            }
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
                if (evidenceScope != null) {
                    log.warn("[InterviewElasticsearchContentRetriever] 分域全文检索失败，按安全边界返回空结果: {}",
                        e.getMessage(), e);
                    return new EmbeddingSearchResult<>(List.of());
                }
                log.warn("[InterviewElasticsearchContentRetriever] 带过滤全文检索失败，退回默认全文检索: {}",
                    e.getMessage(), e);
            }
        }
        if (evidenceScope != null) {
            log.warn("[InterviewElasticsearchContentRetriever] 分域全文检索缺少原生 ES Client，按安全边界返回空结果");
            return new EmbeddingSearchResult<>(List.of());
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
        } catch (ResponseException e) {
            if (isMissingIndex(e)) {
                return new EmbeddingSearchResult<>(List.of());
            }
            throw e;
        }
    }

    private boolean isMissingIndex(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ResponseException responseException
                && responseException.getResponse().getStatusLine().getStatusCode() == 404) {
                return true;
            }
            if (current instanceof ElasticsearchException elasticsearchException
                && elasticsearchException.status() == 404
                && elasticsearchException.error() != null
                && "index_not_found_exception".equals(elasticsearchException.error().type())) {
                return true;
            }
        }
        return false;
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
        if (evidenceScope != null && !matchesEvidenceScope(metadata)) {
            return false;
        }
        if (evidenceScope == null && accessibleUserId != null) {
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

    private ObjectNode buildNativeMetadataFilter() {
        if (objectMapper == null) {
            return null;
        }
        if (evidenceScope != null) {
            return buildNativeEvidenceFilter();
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

    private ObjectNode buildNativeEvidenceFilter() {
        ArrayNode domains = objectMapper.createArrayNode();
        for (EvidenceScope.DomainScope domainScope : evidenceScope.domains()) {
            ArrayNode must = objectMapper.createArrayNode();
            addTerm(must, MetadataKeyConstant.OWNER_USER_ID,
                String.valueOf(evidenceScope.ownerFor(domainScope.domain())));
            addTerm(must, MetadataKeyConstant.DATA_DOMAIN, domainScope.domain().name());
            addTerms(must, MetadataKeyConstant.RESOURCE_ID, domainScope.resourceIds());
            if (!domainScope.resourceVersions().isEmpty()) {
                addTerms(must, MetadataKeyConstant.RESOURCE_VERSION,
                    domainScope.resourceVersions());
            }
            ObjectNode domainBool = objectMapper.createObjectNode();
            domainBool.putObject("bool").set("must", must);
            domains.add(domainBool);
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        bool.set("should", domains);
        bool.put("minimum_should_match", 1);
        return root;
    }

    private void addTerm(ArrayNode clauses, String key, String value) {
        clauses.addObject().putObject("term")
            .putObject("metadata." + key + ".keyword")
            .put("value", value);
    }

    private void addTerms(ArrayNode clauses, String key, Set<String> values) {
        ArrayNode terms = clauses.addObject().putObject("terms")
            .putArray("metadata." + key + ".keyword");
        values.forEach(terms::add);
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

    private Filter buildEvidenceFilter(EvidenceScope scope) {
        return scope.domains().stream()
            .map(domain -> buildEvidenceDomainFilter(scope, domain))
            .reduce((left, right) -> left.or(right))
            .orElseThrow(() -> new IllegalArgumentException("证据域不能为空"));
    }

    private Filter buildEvidenceDomainFilter(
        EvidenceScope scope,
        EvidenceScope.DomainScope domain
    ) {
        Filter result = metadataKey(MetadataKeyConstant.OWNER_USER_ID)
            .isEqualTo(String.valueOf(scope.ownerFor(domain.domain())))
            .and(metadataKey(MetadataKeyConstant.DATA_DOMAIN).isEqualTo(domain.domain().name()))
            .and(orEquals(MetadataKeyConstant.RESOURCE_ID, domain.resourceIds()));
        if (!domain.resourceVersions().isEmpty()) {
            result = result.and(orEquals(
                MetadataKeyConstant.RESOURCE_VERSION, domain.resourceVersions()));
        }
        return result;
    }

    private Filter orEquals(String key, Set<String> values) {
        return values.stream()
            .map(value -> metadataKey(key).isEqualTo(value))
            .reduce((left, right) -> left.or(right))
            .orElseThrow(() -> new IllegalArgumentException(key + " 不能为空"));
    }

    private boolean matchesEvidenceScope(Metadata metadata) {
        String ownerRaw = metadata.getString(MetadataKeyConstant.OWNER_USER_ID);
        String domainRaw = metadata.getString(MetadataKeyConstant.DATA_DOMAIN);
        String resourceId = metadata.getString(MetadataKeyConstant.RESOURCE_ID);
        String resourceVersion = metadata.getString(MetadataKeyConstant.RESOURCE_VERSION);
        if (ownerRaw == null || domainRaw == null || resourceId == null) {
            return false;
        }
        try {
            return evidenceScope.contains(
                DataDomain.valueOf(domainRaw),
                resourceId,
                resourceVersion,
                Long.parseLong(ownerRaw));
        } catch (IllegalArgumentException e) {
            return false;
        }
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
