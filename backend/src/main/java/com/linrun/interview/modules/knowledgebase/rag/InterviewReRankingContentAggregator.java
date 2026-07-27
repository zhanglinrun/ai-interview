package com.linrun.interview.modules.knowledgebase.rag;

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
 * <p>与早期实现的差异仅在于 rerank 模型：早期实现用本地 ONNX BgeScoringModel，
 * 本项目注入 DashScope gte-rerank 实现的 {@code RerankService}（同样实现 ScoringModel）。
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
        // 口播顺序：先 RRF/融合，再精排（各只推一次，避免多 query 重复口播）
        if (progressCallback != null && fusionProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在 RRF 融合...");
        }

        Query query = querySelector.apply(queryToContents);

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
            ? fallback(fusedContents)
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
            log.warn("Rerank 失败，保留 RRF 顺序: {}", e.getMessage());
            return fallback(contents);
        }
        if (!usableScores(scores, contents.size())) {
            log.warn("Rerank 返回不可用分数，保留 RRF 顺序: expected={}, actual={}",
                contents.size(), scores == null ? null : scores.size());
            return fallback(contents);
        }

        List<ScoredContent> scored = new ArrayList<>(contents.size());
        for (int i = 0; i < segments.size(); i++) {
            scored.add(new ScoredContent(contents.get(i), scores.get(i), i));
        }

        return scored.stream()
            .filter(item -> minScore == null || item.score() >= minScore)
            .sorted(Comparator.comparingDouble(ScoredContent::score).reversed()
                .thenComparingInt(ScoredContent::index))
            .map(item -> Content.from(item.content().textSegment(),
                Map.of(RERANKED_SCORE, item.score())))
            .limit(maxResults)
            .toList();
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

    private List<Content> fallback(List<Content> contents) {
        return contents.stream().limit(maxResults).toList();
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
