package com.linrun.interview.rag.service;
import com.linrun.interview.rag.model.InterviewDefaultContent;
import com.linrun.interview.rag.model.RagQueryTrace;
import com.linrun.interview.rag.service.RerankService;


import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static dev.langchain4j.internal.Exceptions.illegalArgument;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.rag.content.ContentMetadata.RERANKED_SCORE;

/**
 * 带重排的内容聚合器（参考业界实现的 ReRankingContentAggregator）。
 *
 * <p>实现 LC4j {@link ContentAggregator}，供 {@code DefaultRetrievalAugmentor} 编排：
 * <ol>
 *   <li>每个 query 内用 {@link ReciprocalRankFuser} 融合多检索源结果</li>
 *   <li>转 {@link InterviewDefaultContent}（按 EMBEDDING_ID 去重）</li>
 *   <li>跨 query 用 {@link InterviewReciprocalRankFuser} 二次 RRF 融合</li>
 *   <li>用 {@link ScoringModel}（即 {@code RerankService}）对融合结果精排，按分过滤+截断</li>
 * </ol>
 *
 * <p>注入本地 ONNX BGE 实现的 {@code RerankService}（同样实现 ScoringModel）。
 */
@Slf4j
public class InterviewReRankingContentAggregator implements ContentAggregator {

    public static final Function<Map<Query, Collection<List<Content>>>, Query> DEFAULT_QUERY_SELECTOR =
        queryToContents -> {
            if (queryToContents.size() > 1) {
                throw illegalArgument(
                    "queryToContents 含 %s 个 query，重排歧义。请显式提供 querySelector。",
                    queryToContents.size());
            }
            return queryToContents.keySet().iterator().next();
        };

    private final ScoringModel scoringModel;
    private final Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
    private final Double minScore;
    private final Integer maxResults;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;
    private final int hybridRrfK;
    private final int fusionRrfK;
    private final int fusionFinalTopK;
    /** RRF / rerank 进度各只发一次（聚合可能对多 query 多次调用 aggregate）。 */
    private final AtomicBoolean fusionProgressSent = new AtomicBoolean(false);
    private final AtomicBoolean rerankProgressSent = new AtomicBoolean(false);

    public InterviewReRankingContentAggregator(ScoringModel scoringModel) {
        this(scoringModel, DEFAULT_QUERY_SELECTOR, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore) {
        this(scoringModel, querySelector, minScore, null, null, null, null, null, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults) {
        this(scoringModel, querySelector, minScore, maxResults, null, null, null, null, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults,
                                                Consumer<String> progressCallback) {
        this(scoringModel, querySelector, minScore, maxResults, progressCallback,
            null, null, null, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults,
                                                Consumer<String> progressCallback,
                                                RagQueryTrace trace,
                                                Integer hybridRrfK,
                                                Integer fusionRrfK,
                                                Integer fusionFinalTopK) {
        this.scoringModel = scoringModel;
        this.querySelector = getOrDefault(querySelector, DEFAULT_QUERY_SELECTOR);
        this.minScore = minScore;
        this.maxResults = getOrDefault(maxResults, Integer.MAX_VALUE);
        this.progressCallback = progressCallback;
        this.trace = trace;
        this.hybridRrfK = getOrDefault(hybridRrfK, 60);
        this.fusionRrfK = getOrDefault(fusionRrfK, 60);
        this.fusionFinalTopK = getOrDefault(fusionFinalTopK, Integer.MAX_VALUE);
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents.isEmpty()) {
            return Collections.emptyList();
        }
        RagQueryTrace.Span span = RagQueryTrace.start(trace, RagQueryTrace.SPAN_RERANK, RagQueryTrace.TYPE_RETRIEVER);
        try {
            List<Content> reranked = aggregateAndRecord(queryToContents, span);
            if (span != null) {
                span.complete(reranked.size() + " docs");
            }
            return reranked;
        } catch (RuntimeException e) {
            if (span != null) {
                span.fail(e.getMessage());
            }
            throw e;
        }
    }

    private List<Content> aggregateAndRecord(Map<Query, Collection<List<Content>>> queryToContents,
                                             RagQueryTrace.Span span) {
        // 口播顺序：先 RRF/融合，再精排（各只推一次，避免多 query 重复口播）
        if (progressCallback != null && fusionProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在 RRF 融合...");
        }

        Query query = querySelector.apply(queryToContents);
        if (span != null) {
            span.input(query == null || query.text() == null ? "" : query.text());
        }

        // 每个 query 内融合多检索源结果
        Map<Query, List<Content>> queryToFusedContents = fuse(queryToContents);

        List<List<InterviewDefaultContent>> queryDefaultContents = queryToFusedContents.values().stream()
            .map(contents -> contents.stream()
                .map(content -> new InterviewDefaultContent((DefaultContent) content))
                .toList())
            .toList();

        if (queryDefaultContents.isEmpty()) {
            return Collections.emptyList();
        }

        // 跨 query 二次 RRF 融合
        List<Content> fusedContents = InterviewReciprocalRankFuser.fuse(
            queryDefaultContents, fusionRrfK);
        if (fusedContents.size() > fusionFinalTopK) {
            fusedContents = fusedContents.subList(0, fusionFinalTopK);
        }

        if (fusedContents.isEmpty()) {
            return fusedContents;
        }

        if (scoringModel != null && progressCallback != null
            && rerankProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在精排...");
        }

        List<Content> reranked = scoringModel == null
            ? headingAwareFallback(fusedContents, query == null ? "" : query.text())
            : reRankAndFilter(fusedContents, query);
        if (trace != null && scoringModel != null) {
            trace.recordReranked(reranked);
        }
        return reranked;
    }

    protected Map<Query, List<Content>> fuse(Map<Query, Collection<List<Content>>> queryToContents) {
        Map<Query, List<Content>> fused = new LinkedHashMap<>();
        for (Query query : queryToContents.keySet()) {
            Collection<List<Content>> contents = queryToContents.get(query);
            List<List<InterviewDefaultContent>> wrapped = contents.stream()
                .map(list -> list.stream()
                    .map(content -> new InterviewDefaultContent((DefaultContent) content))
                    .toList())
                .toList();
            fused.put(query, InterviewReciprocalRankFuser.fuse(wrapped, hybridRrfK));
        }
        return fused;
    }

    protected List<Content> reRankAndFilter(List<Content> contents, Query query) {
        List<TextSegment> segments = contents.stream().map(Content::textSegment).toList();

        List<Double> scores;
        try {
            scores = scoringModel.scoreAll(segments, query.text()).content();
        } catch (Exception e) {
            log.warn("Rerank 失败，回退标题重合排序: {}", e.getMessage());
            return headingAwareFallback(contents, query.text());
        }
        if (!usableScores(scores, contents.size())) {
            log.warn("Rerank 返回不可用分数，回退标题重合排序: expected={}, actual={}",
                contents.size(), scores == null ? null : scores.size());
            return headingAwareFallback(contents, query.text());
        }

        List<Double> unitScores = RerankScoreNormalizer.toUnitInterval(scores);
        List<ScoredContent> scored = new ArrayList<>(contents.size());
        for (int i = 0; i < segments.size(); i++) {
            scored.add(new ScoredContent(contents.get(i), unitScores.get(i), i));
        }

        String queryText = query == null ? "" : query.text();
        List<ScoredContent> ranked = scored.stream()
            .filter(item -> minScore == null || item.score() >= minScore)
            .sorted(Comparator
                .comparingInt((ScoredContent item) -> headingAlignment(
                    firstHeading(item.content().textSegment().text()), queryText))
                .reversed()
                .thenComparing(Comparator.comparingDouble(ScoredContent::score).reversed())
                .thenComparingInt(ScoredContent::index))
            .toList();
        if (ranked.isEmpty() && !contents.isEmpty()) {
            log.warn("Rerank 全部低于 minScore={}，回退标题重合排序 top-{}", minScore, maxResults);
            return headingAwareFallback(contents, query.text());
        }
        return toLimitedContents(dropConfusedHeadings(ranked, queryText), maxResults);
    }

    /**
     * BGE 不可用或全员低于阈值时，优先保留标题/首行与问句字面重合更高的块。
     * 重合为 0 时保持 RRF 顺序，避免空结果误拒。
     */
    List<Content> headingAwareFallback(List<Content> contents, String query) {
        String compactQuery = compactForOverlap(query);
        List<ScoredContent> ranked = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            String heading = firstHeading(contents.get(i).textSegment().text());
            int alignment = headingAlignment(heading, query);
            int overlap = headingOverlap(heading, compactQuery);
            ranked.add(new ScoredContent(contents.get(i), alignment * 100 + overlap, i));
        }
        ranked.sort(Comparator.comparingDouble(ScoredContent::score).reversed()
            .thenComparingInt(ScoredContent::index));
        return dropConfusedHeadings(ranked, query).stream()
            .map(ScoredContent::content)
            .limit(maxResults)
            .toList();
    }

    /**
     * 问句只点名「缓存穿透」时，丢掉标题是「缓存击穿/雪崩」的块。
     * 面渣里击穿段落会写「穿透缓存」，只往后排仍会占 Top-K、拉低精确率。
     * 对比题（一问里同时出现多个词）不改序。只剩近义标题时不丢，避免空结果。
     */
    private List<ScoredContent> dropConfusedHeadings(List<ScoredContent> ranked, String query) {
        List<ScoredContent> kept = ranked.stream()
            .filter(item -> headingAlignment(
                firstHeading(item.content().textSegment().text()), query) >= 0)
            .toList();
        return kept.isEmpty() ? ranked : kept;
    }

    private List<Content> toLimitedContents(List<ScoredContent> ranked, int limit) {
        return ranked.stream()
            .map(item -> Content.from(item.content().textSegment(),
                Map.of(RERANKED_SCORE, item.score())))
            .limit(limit)
            .toList();
    }

    /**
     * 问句只点名「缓存穿透」时，标题是「缓存击穿/雪崩」的块对齐分为 -1。
     */
    static int headingAlignment(String heading, String query) {
        String asked = askedConfusionTerm(query);
        if (asked == null || heading == null || heading.isBlank()) {
            return 0;
        }
        if (heading.contains(asked)) {
            return 1;
        }
        for (String rival : CONFUSION_TERMS) {
            if (!rival.equals(asked) && heading.contains(rival)) {
                return -1;
            }
        }
        return 0;
    }

    static String askedConfusionTerm(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> hits = new ArrayList<>();
        for (String term : CONFUSION_TERMS) {
            if (query.contains(term)) {
                hits.add(term);
            }
        }
        return hits.size() == 1 ? hits.getFirst() : null;
    }

    private static final List<String> CONFUSION_TERMS = List.of("缓存穿透", "缓存击穿", "缓存雪崩");

    static String firstHeading(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String line : text.split("\\R", 8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
            return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
        }
        return "";
    }

    static int headingOverlap(String heading, String compactQuery) {
        String compactHeading = compactForOverlap(heading);
        if (compactHeading.isEmpty() || compactQuery == null || compactQuery.isEmpty()) {
            return 0;
        }
        if (compactHeading.contains(compactQuery) || compactQuery.contains(compactHeading)) {
            return Math.min(compactHeading.length(), compactQuery.length());
        }
        int maxN = Math.min(16, Math.min(compactHeading.length(), compactQuery.length()));
        for (int n = maxN; n >= 4; n--) {
            for (int i = 0; i + n <= compactQuery.length(); i++) {
                if (compactHeading.contains(compactQuery.substring(i, i + n))) {
                    return n;
                }
            }
        }
        return 0;
    }

    static String compactForOverlap(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}#？?。，、；;：:！!（）()【】\\[\\]《》<>·…]+", "")
            .toLowerCase(java.util.Locale.ROOT);
    }

    private boolean usableScores(List<Double> scores, int expectedSize) {
        if (scores == null || scores.size() != expectedSize || scores.isEmpty()) {
            return false;
        }
        Double first = null;
        boolean hasVariation = false;
        for (Double score : scores) {
            if (score == null || !Double.isFinite(score)) {
                return false;
            }
            if (first == null) {
                first = score;
            } else if (Double.compare(first, score) != 0) {
                hasVariation = true;
            }
        }
        return scores.size() == 1 || hasVariation;
    }

    private record ScoredContent(Content content, double score, int index) {
    }

    public static ReRankingContentAggregatorBuilder builder() {
        return new ReRankingContentAggregatorBuilder();
    }

    public static class ReRankingContentAggregatorBuilder {
        private ScoringModel scoringModel;
        private Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
        private Double minScore;
        private Integer maxResults;
        private Consumer<String> progressCallback;
        private RagQueryTrace trace;
        private Integer hybridRrfK;
        private Integer fusionRrfK;
        private Integer fusionFinalTopK;

        public ReRankingContentAggregatorBuilder scoringModel(ScoringModel scoringModel) {
            this.scoringModel = scoringModel;
            return this;
        }

        public ReRankingContentAggregatorBuilder querySelector(
            Function<Map<Query, Collection<List<Content>>>, Query> querySelector) {
            this.querySelector = querySelector;
            return this;
        }

        public ReRankingContentAggregatorBuilder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        public ReRankingContentAggregatorBuilder maxResults(Integer maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public ReRankingContentAggregatorBuilder progressCallback(Consumer<String> progressCallback) {
            this.progressCallback = progressCallback;
            return this;
        }

        public ReRankingContentAggregatorBuilder trace(RagQueryTrace trace) {
            this.trace = trace;
            return this;
        }

        public ReRankingContentAggregatorBuilder hybridRrfK(Integer hybridRrfK) {
            this.hybridRrfK = hybridRrfK;
            return this;
        }

        public ReRankingContentAggregatorBuilder fusionRrfK(Integer fusionRrfK) {
            this.fusionRrfK = fusionRrfK;
            return this;
        }

        public ReRankingContentAggregatorBuilder fusionFinalTopK(Integer fusionFinalTopK) {
            this.fusionFinalTopK = fusionFinalTopK;
            return this;
        }

        public InterviewReRankingContentAggregator build() {
            return new InterviewReRankingContentAggregator(scoringModel, querySelector, minScore,
                maxResults, progressCallback, trace, hybridRrfK, fusionRrfK, fusionFinalTopK);
        }
    }
}
