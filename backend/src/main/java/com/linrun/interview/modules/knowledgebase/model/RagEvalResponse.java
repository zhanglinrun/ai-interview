package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

public record RagEvalResponse(
    String runId,
    int total,
    int k,
    double hitRate,
    double mrr,
    double ndcg,
    List<ItemResult> items
) {
    public RagEvalResponse(int total, int k, double hitRate, double mrr, double ndcg,
                           List<ItemResult> items) {
        this(null, total, k, hitRate, mrr, ndcg, items);
    }

    public record ItemResult(
        String question,
        boolean hit,
        int firstHitRank,
        double reciprocalRank,
        double ndcg,
        List<String> retrievedChunkIds
    ) {}
}
