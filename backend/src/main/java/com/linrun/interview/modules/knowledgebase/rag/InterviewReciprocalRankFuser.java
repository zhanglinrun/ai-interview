package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.internal.ValidationUtils.ensureBetween;

/**
 * Reciprocal Rank Fusion 融合器（参考业界实现的 ReciprocalRankFuser）。
 *
 * <p>把多路召回的 {@code List<InterviewDefaultContent>} 按 RRF 公式 {@code 1/(k+rank)}
 * 跨路累加，分高者优先；去重依赖 {@link InterviewDefaultContent} 按 EMBEDDING_ID 的
 * equals/hashCode，使同一 chunk 跨多路命中时分数累加而非重复保留。
 *
 * @see <a href="https://learn.microsoft.com/en-us/azure/search/hybrid-search-ranking">RRF 说明</a>
 */
public final class InterviewReciprocalRankFuser {

    private InterviewReciprocalRankFuser() {
    }

    /**
     * 默认 k=60 融合。
     */
    public static List<Content> fuse(Collection<List<InterviewDefaultContent>> listsOfContents) {
        return fuse(listsOfContents, 60);
    }

    /**
     * @param listsOfContents 每路召回结果（已按相关性降序）
     * @param k               RRF 常数，越大越平滑各路排名差异，须 >= 1
     * @return 按融合分降序的 Content 列表
     */
    public static List<Content> fuse(Collection<List<InterviewDefaultContent>> listsOfContents, int k) {
        ensureBetween(k, 1, Integer.MAX_VALUE, "k");

        Map<Content, Double> scores = new LinkedHashMap<>();
        for (List<InterviewDefaultContent> singleListOfContent : listsOfContents) {
            for (int i = 0; i < singleListOfContent.size(); i++) {
                Content content = singleListOfContent.get(i);
                double currentScore = scores.getOrDefault(content, 0.0);
                int rank = i + 1;
                double newScore = currentScore + 1.0 / (k + rank);
                scores.put(content, newScore);
            }
        }

        List<Content> fused = new ArrayList<>(scores.keySet());
        fused.sort(Comparator.comparingDouble(scores::get).reversed());
        return fused;
    }
}
