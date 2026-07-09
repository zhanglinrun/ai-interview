package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.ArrayList;
import java.util.List;

public class RagQueryTrace {

    private String rewrittenQuestion;
    private String routeStrategy;
    private String routeReasoning;
    private final List<String> decomposedQueries = new ArrayList<>();
    private String cragGrade;
    private String cragAction;
    private boolean graphAttempted;
    private boolean graphHit;
    private String graphResult;
    private final List<TraceContent> retrieved = new ArrayList<>();
    private final List<TraceContent> reranked = new ArrayList<>();

    public String rewrittenQuestion() {
        return rewrittenQuestion;
    }

    public void rewrittenQuestion(String rewrittenQuestion) {
        this.rewrittenQuestion = rewrittenQuestion;
    }

    public List<String> decomposedQueries() {
        return List.copyOf(decomposedQueries);
    }

    public void decomposedQueries(List<String> queries) {
        this.decomposedQueries.clear();
        if (queries != null) {
            this.decomposedQueries.addAll(queries);
        }
    }

    public String cragGrade() {
        return cragGrade;
    }

    public String cragAction() {
        return cragAction;
    }

    /** 记录 CRAG 打分与纠正动作（none / rewrite_retry / fallback_no_evidence）。 */
    public void crag(String grade, String action) {
        this.cragGrade = grade;
        this.cragAction = action;
    }

    public boolean graphAttempted() {
        return graphAttempted;
    }

    public boolean graphHit() {
        return graphHit;
    }

    public String graphResult() {
        return graphResult;
    }

    /**
     * 记录图谱（Neo4j Text2Cypher）参与情况。
     *
     * @param attempted 是否尝试了图谱检索
     * @param hit       图谱是否命中（false 表示为空或异常，已降级向量检索）
     * @param result    命中时的 Cypher 结果原文（会截断成片段）
     */
    public void graph(boolean attempted, boolean hit, String result) {
        this.graphAttempted = attempted;
        this.graphHit = hit;
        this.graphResult = result == null ? null : snippet(result);
    }

    public String routeStrategy() {
        return routeStrategy;
    }

    public String routeReasoning() {
        return routeReasoning;
    }

    public void route(String strategy, String reasoning) {
        this.routeStrategy = strategy;
        this.routeReasoning = reasoning;
    }

    public List<TraceContent> retrieved() {
        return List.copyOf(retrieved);
    }

    public List<TraceContent> reranked() {
        return List.copyOf(reranked);
    }

    public void recordRetrieved(List<Content> contents) {
        this.retrieved.clear();
        this.retrieved.addAll(toTraceContents(contents));
    }

    public void recordReranked(List<Content> contents) {
        this.reranked.clear();
        this.reranked.addAll(toTraceContents(contents));
    }

    private List<TraceContent> toTraceContents(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<TraceContent> result = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            var meta = content.textSegment().metadata();
            result.add(new TraceContent(
                i + 1,
                meta.getString("docId"),
                meta.getString("chunkId"),
                score(content),
                rerankScore(content),
                snippet(content.textSegment().text())));
        }
        return result;
    }

    private Double score(Content content) {
        Object score = content.metadata().get(ContentMetadata.SCORE);
        return score instanceof Number number ? round(number.doubleValue()) : null;
    }

    private Double rerankScore(Content content) {
        Object score = content.metadata().get(ContentMetadata.RERANKED_SCORE);
        return score instanceof Number number ? round(number.doubleValue()) : null;
    }

    private Double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    public record TraceContent(
        int rank,
        String docId,
        String chunkId,
        Double score,
        Double rerankScore,
        String snippet
    ) {}
}
