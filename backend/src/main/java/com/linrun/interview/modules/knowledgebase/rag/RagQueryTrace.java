package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.evidence.EvidencePacket;
import com.linrun.interview.common.evidence.EvidenceRef;
import com.linrun.interview.common.evidence.EvidenceScope;
import com.linrun.interview.common.evidence.EvidenceStatus;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.ArrayList;
import java.util.List;

public class RagQueryTrace {

    private String rewrittenQuestion;
    private final List<String> decomposedQueries = new ArrayList<>();
    private String cragGrade;
    private String cragAction;
    private final List<TraceContent> retrieved = new ArrayList<>();
    private final List<TraceContent> reranked = new ArrayList<>();
    private EvidenceScope evidenceScope;
    private EvidenceStatus evidenceStatus;
    private List<EvidenceRef> evidenceRefs = List.of();
    private List<String> degradedReasons = List.of();

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

    public void recordEvidencePacket(EvidenceScope scope, EvidencePacket packet) {
        this.evidenceScope = scope;
        this.evidenceStatus = packet != null ? packet.status() : EvidenceStatus.NONE;
        this.evidenceRefs = packet != null ? packet.evidenceRefs() : List.of();
        this.degradedReasons = packet != null ? packet.degradedReasons() : List.of();
    }

    public EvidenceScope evidenceScope() {
        return evidenceScope;
    }

    public EvidenceStatus evidenceStatus() {
        return evidenceStatus;
    }

    public List<EvidenceRef> evidenceRefs() {
        return List.copyOf(evidenceRefs);
    }

    public List<String> degradedReasons() {
        return List.copyOf(degradedReasons);
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
                meta.getString("dataDomain"),
                meta.getString("resourceId"),
                meta.getString("resourceVersion"),
                meta.getString("evidenceId"),
                meta.getString("contentHash"),
                meta.getString("sourceLocator"),
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
        String dataDomain,
        String resourceId,
        String resourceVersion,
        String evidenceId,
        String contentHash,
        String sourceLocator,
        Double score,
        Double rerankScore,
        String snippet
    ) {}
}
