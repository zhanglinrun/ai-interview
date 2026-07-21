package com.linrun.interview.modules.knowledgebase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RagEvalResponse(
    String runId,
    int total,
    int k,
    double hitRate,
    double mrr,
    double ndcg,
    double retrievalRecall,
    double retrievalPrecision,
    List<ItemResult> items
) {
    public RagEvalResponse(int total, int k, double hitRate, double mrr, double ndcg,
                           List<ItemResult> items) {
        this(null, total, k, hitRate, mrr, ndcg, 0.0, 0.0, items);
    }

    /** @deprecated 检索阶段未生成答案，旧字段实际表示期望证据召回率。 */
    @Deprecated
    @JsonProperty("citationHitRate")
    public double citationHitRate() {
        return retrievalRecall;
    }

    /** @deprecated 检索阶段未生成答案，旧字段实际表示检索精确率。 */
    @Deprecated
    @JsonProperty("citationCoverage")
    public double citationCoverage() {
        return retrievalPrecision;
    }

    public record ItemResult(
        String question,
        boolean hit,
        int firstHitRank,
        double reciprocalRank,
        double ndcg,
        double retrievalRecall,
        double retrievalPrecision,
        List<String> retrievedChunkIds,
        List<RetrievedSegment> retrievedSegments
    ) {
        /** @deprecated 检索阶段未生成答案，旧字段实际表示期望证据召回率。 */
        @Deprecated
        @JsonProperty("citationHitRate")
        public double citationHitRate() {
            return retrievalRecall;
        }

        /** @deprecated 检索阶段未生成答案，旧字段实际表示检索精确率。 */
        @Deprecated
        @JsonProperty("citationCoverage")
        public double citationCoverage() {
            return retrievalPrecision;
        }
    }

    public record RetrievedSegment(
        int rank,
        String chunkId,
        Long docId,
        String snippet,
        Double score
    ) {}
}
