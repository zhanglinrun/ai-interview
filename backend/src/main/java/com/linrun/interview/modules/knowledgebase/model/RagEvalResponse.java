package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

public record RagEvalResponse(
    String runId,
    int total,
    int k,
    double hitRate,
    double mrr,
    double ndcg,
    double citationHitRate,
    double citationCoverage,
    List<ItemResult> items
) {
    public RagEvalResponse(int total, int k, double hitRate, double mrr, double ndcg,
                           List<ItemResult> items) {
        this(null, total, k, hitRate, mrr, ndcg, 0.0, 0.0, items);
    }

    public record ItemResult(
        String question,
        boolean hit,
        int firstHitRank,
        double reciprocalRank,
        double ndcg,
        double citationHitRate,
        double citationCoverage,
        List<String> retrievedChunkIds,
        List<RetrievedSegment> retrievedSegments
    ) {}

    public record RetrievedSegment(
        int rank,
        String chunkId,
        Long docId,
        String snippet,
        Double score
    ) {}
}
