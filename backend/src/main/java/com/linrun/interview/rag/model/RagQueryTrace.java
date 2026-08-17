package com.linrun.interview.rag.model;

import com.linrun.interview.rag.model.EvidencePacket;
import com.linrun.interview.rag.model.EvidenceRef;
import com.linrun.interview.rag.model.EvidenceScope;
import com.linrun.interview.rag.model.EvidenceStatus;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RagQueryTrace {

    public static final String SPAN_INTENT = "INTENT";
    public static final String SPAN_REWRITE = "REWRITE";
    public static final String SPAN_ROUTE = "ROUTE";
    public static final String SPAN_RETRIEVAL = "RETRIEVAL";
    public static final String SPAN_RERANK = "RERANK";
    public static final String SPAN_GENERATE = "GENERATE";
    public static final String SPAN_CITATION = "CITATION";

    public static final String TYPE_SPAN = "span";
    public static final String TYPE_RETRIEVER = "retriever";
    public static final String TYPE_GENERATION = "generation";

    private final List<Span> spans = new CopyOnWriteArrayList<>();
    private String rewrittenQuestion;
    private final List<String> decomposedQueries = new ArrayList<>();
    private String cragGrade;
    private String cragAction;
    private String routeSource;
    private String routeIntent;
    private Double routeConfidence;
    private String routeReasoning;
    private final List<TraceContent> retrieved = new ArrayList<>();
    private final List<TraceContent> reranked = new ArrayList<>();
    private EvidenceScope evidenceScope;
    private EvidenceStatus evidenceStatus;
    private List<EvidenceRef> evidenceRefs = List.of();
    private List<String> degradedReasons = List.of();

    public static Span start(RagQueryTrace trace, String name, String type) {
        return trace == null ? null : trace.startSpan(name, type);
    }

    public Span startSpan(String name, String type) {
        Span span = new Span(name, type);
        spans.add(span);
        return span;
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

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

    /** 记录本次查询的主数据源路由决策。 */
    public void route(String source, String intent, double confidence, String reasoning) {
        this.routeSource = source;
        this.routeIntent = intent == null ? null
            : intent.length() <= 120 ? intent : intent.substring(0, 120);
        this.routeConfidence = Double.isFinite(confidence)
            ? Math.max(0.0, Math.min(1.0, confidence)) : null;
        this.routeReasoning = reasoning == null ? null
            : reasoning.length() <= 500 ? reasoning : reasoning.substring(0, 500);
    }

    public String routeSource() {
        return routeSource;
    }

    public String routeIntent() {
        return routeIntent;
    }

    public Double routeConfidence() {
        return routeConfidence;
    }

    public String routeReasoning() {
        return routeReasoning;
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

    public static final class Span {
        private final String name;
        private final String type;
        private final Instant startedAt;
        private volatile Instant completedAt;
        private volatile String input;
        private volatile String output;
        private volatile String status;
        private volatile String errorMessage;
        private volatile String dataSource;
        private volatile String provider;
        private volatile String modelName;
        private volatile Double confidence;

        Span(String name, String type) {
            this.name = name;
            this.type = type == null ? TYPE_SPAN : type;
            this.startedAt = Instant.now();
        }

        public Span input(String input) {
            this.input = input;
            return this;
        }

        public Span dataSource(String dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public Span confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public void complete(String output) {
            complete(output, "COMPLETED");
        }

        public void complete(String output, String status) {
            if (closed()) {
                return;
            }
            this.output = output;
            this.status = status == null ? "COMPLETED" : status;
            this.completedAt = Instant.now();
        }

        public void fail(String error) {
            if (closed()) {
                return;
            }
            this.errorMessage = error;
            this.status = "FAILED";
            this.completedAt = Instant.now();
        }

        public boolean closed() {
            return completedAt != null;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public String input() {
            return input;
        }

        public String output() {
            return output;
        }

        public String status() {
            return status;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public String dataSource() {
            return dataSource;
        }

        public String provider() {
            return provider;
        }

        public String modelName() {
            return modelName;
        }

        public Double confidence() {
            return confidence;
        }

        public long latencyMs() {
            Instant end = completedAt != null ? completedAt : Instant.now();
            return Math.max(0L, Duration.between(startedAt, end).toMillis());
        }

        public LocalDateTime startedAtLocal() {
            return LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault());
        }

        public LocalDateTime completedAtLocal() {
            Instant end = completedAt != null ? completedAt : Instant.now();
            return LocalDateTime.ofInstant(end, ZoneId.systemDefault());
        }
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
