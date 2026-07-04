package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Exceptions.illegalArgument;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
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
    /** rerank 进度只发一次（聚合可能对多 query 多次调用 aggregate）。 */
    private final AtomicBoolean rerankProgressSent = new AtomicBoolean(false);

    public InterviewReRankingContentAggregator(ScoringModel scoringModel) {
        this(scoringModel, DEFAULT_QUERY_SELECTOR, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore) {
        this(scoringModel, querySelector, minScore, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults) {
        this(scoringModel, querySelector, minScore, maxResults, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults,
                                                Consumer<String> progressCallback) {
        this(scoringModel, querySelector, minScore, maxResults, progressCallback, null);
    }

    public InterviewReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults,
                                                Consumer<String> progressCallback,
                                                RagQueryTrace trace) {
        this.scoringModel = ensureNotNull(scoringModel, "scoringModel");
        this.querySelector = getOrDefault(querySelector, DEFAULT_QUERY_SELECTOR);
        this.minScore = minScore;
        this.maxResults = getOrDefault(maxResults, Integer.MAX_VALUE);
        this.progressCallback = progressCallback;
        this.trace = trace;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents.isEmpty()) {
            return Collections.emptyList();
        }
        // 亮点2：rerank 前推一次"正在排序筛选结果"进度
        if (progressCallback != null && rerankProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在排序筛选结果...");
        }

        Query query = querySelector.apply(queryToContents);

        // 每个 query 内融合多检索源结果
        Map<Query, List<Content>> queryToFusedContents = fuse(queryToContents);

        List<List<InterviewDefaultContent>> 参考实现DefaultContents = queryToFusedContents.values().stream()
            .map(contents -> contents.stream()
                .map(content -> new InterviewDefaultContent((DefaultContent) content))
                .toList())
            .toList();

        if (参考实现DefaultContents.isEmpty()) {
            return Collections.emptyList();
        }

        // 跨 query 二次 RRF 融合
        List<Content> fusedContents = InterviewReciprocalRankFuser.fuse(参考实现DefaultContents);

        if (fusedContents.isEmpty()) {
            return fusedContents;
        }

        List<Content> reranked = reRankAndFilter(fusedContents, query);
        if (trace != null) {
            trace.recordReranked(reranked);
        }
        return reranked;
    }

    protected Map<Query, List<Content>> fuse(Map<Query, Collection<List<Content>>> queryToContents) {
        Map<Query, List<Content>> fused = new LinkedHashMap<>();
        for (Query query : queryToContents.keySet()) {
            Collection<List<Content>> contents = queryToContents.get(query);
            fused.put(query, ReciprocalRankFuser.fuse(contents));
        }
        return fused;
    }

    protected List<Content> reRankAndFilter(List<Content> contents, Query query) {
        List<TextSegment> segments = contents.stream()
            .map(Content::textSegment)
            .collect(Collectors.toList());

        List<Double> scores = scoringModel.scoreAll(segments, query.text()).content();

        Map<TextSegment, Double> segmentToScore = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            segmentToScore.put(segments.get(i), scores.get(i));
        }

        return segmentToScore.entrySet().stream()
            .filter(entry -> minScore == null || entry.getValue() >= minScore)
            .sorted(Map.Entry.<TextSegment, Double>comparingByValue().reversed())
            .map(entry -> Content.from(entry.getKey(), Map.of(RERANKED_SCORE, entry.getValue())))
            .limit(maxResults)
            .collect(Collectors.toList());
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

        public InterviewReRankingContentAggregator build() {
            return new InterviewReRankingContentAggregator(scoringModel, querySelector, minScore,
                maxResults, progressCallback, trace);
        }
    }
}
