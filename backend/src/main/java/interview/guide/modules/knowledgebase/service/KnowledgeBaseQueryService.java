package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.FluxStreamingBridge;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.PromptTemplate;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagSourceDTO;
import interview.guide.modules.knowledgebase.rag.InterviewElasticsearchContentRetriever;
import interview.guide.modules.knowledgebase.rag.InterviewQueryRouter;
import interview.guide.modules.knowledgebase.rag.InterviewQueryTransformer;
import interview.guide.modules.knowledgebase.rag.InterviewReRankingContentAggregator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库查询服务（LangChain4j RetrievalAugmentor 编排版，对齐 know-engine）。
 *
 * <p>检索/改写/融合/rerank 不再手写，改用 {@link DefaultRetrievalAugmentor} 编排
 * （{@link InterviewQueryTransformer} 改写 → {@link InterviewQueryRouter} 路由 →
 * {@link InterviewElasticsearchContentRetriever} ES 检索 → {@link InterviewReRankingContentAggregator}
 * RRF 融合 + DashScope rerank → {@link DefaultContentInjector} 注入）。
 *
 * <p>同步 {@link #queryKnowledgeBase} 手动调 {@code augmentor.augment} 拿 {@code AugmentationResult}
 * （含检索 contents 与注入后的 chatMessage），再调 {@link ChatModel} 生成，用 contents 构建 sources/citation。
 * 流式 {@link #answerQuestionStream} 同样手动 augment 后用 {@link FluxStreamingBridge} 流式生成，
 * 流末尾拼接 sourcesMarkdown。统一手动编排以拿到检索中间结果。
 *
 * <p>Spring AI {@link Document} 已全部替换为 LC4j {@link Content}/{@link TextSegment}。
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int SOURCE_SNIPPET_MAX_CHARS = 220;
    private static final String CITATION_INSTRUCTION = """

# Citation (引用标注)
上下文每个片段前都标有 [n] 编号（从 [1] 开始）。回答时：
- 每一条基于检索内容的客观陈述，都要在相关句末用方括号标注其来源编号，例如 [1]、[2]；一条陈述引用多个来源时写成 [1][2]。
- 只能使用上下文中真实出现的编号，严禁编造不存在的编号。
- 与检索内容无关的过渡或总结性语句可不标注。
""";
    private static final String SESSION_ID_DEFAULT = "default";

    private final LlmProviderRegistry llmProviderRegistry;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final RerankService rerankService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int topk;
    private final double minScore;
    private final boolean rerankEnabled;
    private final boolean citationEnabled;
    private final double citationCoverageWeight;
    private final double citationInvalidPenalty;
    private final CitationAnalyzer citationAnalyzer;
    private final int rerankTopN;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            ElasticsearchEmbeddingStore embeddingStore,
            EmbeddingModel embeddingModel,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            RerankService rerankService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            @Autowired(required = false)
            MeterRegistry meterRegistry) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.listService = listService;
        this.countService = countService;
        this.rerankService = rerankService;
        this.meterRegistry = meterRegistry;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.topk = queryProperties.getSearch().getTopkMedium();
        this.minScore = queryProperties.getSearch().getMinScoreDefault();
        this.rerankEnabled = queryProperties.getRerank().isEnabled();
        this.citationEnabled = queryProperties.getCitation().isEnabled();
        this.citationCoverageWeight = queryProperties.getCitation().getCoverageWeight();
        this.citationInvalidPenalty = queryProperties.getCitation().getInvalidPenalty();
        this.citationAnalyzer = new CitationAnalyzer(citationCoverageWeight, citationInvalidPenalty);
        this.rerankTopN = queryProperties.getRerank().getTopN();
    }

    private ChatModel getChatModel() {
        return llmProviderRegistry.getDefaultChatModel();
    }

    private StreamingChatModel getStreamingChatModel() {
        return llmProviderRegistry.getDefaultStreamingChatModel();
    }

    /**
     * 基于单个知识库回答用户问题
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG，无历史上下文）
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return answerQuestion(knowledgeBaseIds, question, List.of());
    }

    /**
     * 基于多个知识库回答用户问题（RAG，带历史上下文）。供非 Controller 路径复用。
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        countService.updateQuestionCounts(knowledgeBaseIds);
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, history);
        if (outcome.contents().isEmpty()) {
            return NO_RESULT_RESPONSE;
        }
        return generateAnswer(knowledgeBaseIds, question, outcome);
    }

    /**
     * RAG 评测专用问答入口：复用真实检索和生成链路，但不更新业务计数。
     */
    public String answerQuestionForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of());
        if (outcome.contents().isEmpty()) {
            return NO_RESULT_RESPONSE;
        }
        return generateAnswer(knowledgeBaseIds, question, outcome);
    }

    /**
     * RAG 评测专用检索入口：只返回检索到的片段、不生成答案，供评测计算 Hit/MRR/NDCG。
     */
    public List<TextSegment> retrieveForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return List.of();
        }
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of());
        return outcome.contents().stream().map(Content::textSegment).toList();
    }

    /**
     * 查询知识库并返回完整响应（含 sources/citation）
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds();
        String question = request.question();

        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return new QueryResponse(NO_RESULT_RESPONSE, null, "", List.of(), null, List.of());
        }

        List<String> kbNames = listService.getKnowledgeBaseNames(knowledgeBaseIds);
        String kbNamesStr = String.join("、", kbNames);
        Long primaryKbId = knowledgeBaseIds.getFirst();

        if (normalizeQuestion(question).isBlank()) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of());
        List<Content> contents = outcome.contents();
        if (contents.isEmpty()) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        String answer = generateAnswer(knowledgeBaseIds, question, outcome);
        CitationAnalyzer.CitationAnalysis citation = citationEnabled
            ? citationAnalyzer.analyze(answer, contents.size())
            : new CitationAnalyzer.CitationAnalysis(List.of(), List.of(), 0.0d);
        Double confidence = citationEnabled
            ? citationAnalyzer.confidence(
                contents.stream().map(this::extractSimilarity).toList(), citation)
            : null;
        List<RagSourceDTO> sources = buildSources(contents, Set.copyOf(citation.citedIndexes()));
        return new QueryResponse(answer, primaryKbId, kbNamesStr, sources, confidence, citation.invalidIndexes());
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            countService.updateQuestionCounts(knowledgeBaseIds);
            AugmentationOutcome outcome = augment(knowledgeBaseIds, question, history);
            List<Content> contents = outcome.contents();
            if (contents.isEmpty()) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            log.debug("检索到 {} 个相关片段", contents.size());
            Flux<String> responseFlux = streamGenerate(outcome);

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            Flux<String> normalizedFlux = normalizeStreamOutput(responseFlux);
            String sourcesMarkdown = buildSourcesMarkdown(contents);
            if (!sourcesMarkdown.isBlank()) {
                normalizedFlux = normalizedFlux.concatWith(Flux.just(sourcesMarkdown));
            }

            return instrumentStream(normalizedFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });
        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    // ========== RetrievalAugmentor 编排 ==========

    /**
     * 构建 RetrievalAugmentor 并执行 augment，返回检索到的 contents 与注入后的 chatMessage。
     */
    private AugmentationOutcome augment(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history) {
        RetrievalAugmentor augmentor = buildAugmentor(knowledgeBaseIds, history);
        UserMessage userMessage = UserMessage.from(question);
        Metadata metadata = Metadata.from(userMessage, SESSION_ID_DEFAULT, history);
        dev.langchain4j.rag.AugmentationResult result =
            augmentor.augment(new dev.langchain4j.rag.AugmentationRequest(userMessage, metadata));
        return new AugmentationOutcome(result.contents(), result.chatMessage());
    }

    private RetrievalAugmentor buildAugmentor(List<Long> knowledgeBaseIds, List<ChatMessage> history) {
        InterviewElasticsearchContentRetriever retriever = new InterviewElasticsearchContentRetriever(
            embeddingStore, embeddingModel, topk, minScore, knowledgeBaseIds);
        InterviewQueryTransformer transformer = new InterviewQueryTransformer(
            getChatModel(), rewritePromptTemplate, rewriteEnabled);
        InterviewReRankingContentAggregator aggregator = rerankEnabled && rerankService.isEnabled()
            ? InterviewReRankingContentAggregator.builder()
                .scoringModel(rerankService)
                .maxResults(rerankTopN > 0 ? rerankTopN : null)
                .querySelector(qtc -> qtc.keySet().iterator().next())
                .build()
            : null;
        ContentInjector contentInjector = new DefaultContentInjector();

        DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder builder = DefaultRetrievalAugmentor.builder()
            .queryRouter(new InterviewQueryRouter(retriever))
            .queryTransformer(transformer)
            .contentInjector(contentInjector);
        if (aggregator != null) {
            builder.contentAggregator(aggregator);
        }
        return builder.build();
    }

    // ========== 生成 ==========

    private String generateAnswer(List<Long> knowledgeBaseIds, String question, AugmentationOutcome outcome) {
        try {
            String answer = getChatModel().chat(ChatRequest.builder()
                    .messages(SystemMessage.from(buildSystemPrompt()), outcome.chatMessage())
                    .build())
                .aiMessage().text();
            answer = normalizeAnswer(answer);
            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;
        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "知识库查询失败：" + e.getMessage(), e);
        }
    }

    private Flux<String> streamGenerate(AugmentationOutcome outcome) {
        return FluxStreamingBridge.stream(getStreamingChatModel(),
            ChatRequest.builder()
                .messages(SystemMessage.from(buildSystemPrompt()), outcome.chatMessage())
                .build());
    }

    private String buildSystemPrompt() {
        String prompt = systemPromptTemplate.render() + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        if (citationEnabled) {
            prompt += CITATION_INSTRUCTION;
        }
        return prompt;
    }

    // ========== sources / citation / 元信息 ==========

    private List<RagSourceDTO> buildSources(List<Content> contents, Set<Integer> citedIndexes) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<Long> knowledgeBaseIds = contents.stream()
            .map(this::extractKnowledgeBaseId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        var nameMap = listService.getKnowledgeBaseNameMap(knowledgeBaseIds);

        List<RagSourceDTO> sources = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            TextSegment segment = content.textSegment();
            Long knowledgeBaseId = extractKnowledgeBaseId(content);
            var metadata = segment.metadata();
            String fallbackTitle = knowledgeBaseId == null
                ? "未知知识库" : nameMap.getOrDefault(knowledgeBaseId, "未知知识库");
            String documentTitle = firstNonBlank(metadata.getString("fileName"), fallbackTitle);
            String category = metadata.getString("category");
            boolean cited = citedIndexes != null && citedIndexes.contains(i + 1);
            sources.add(new RagSourceDTO(
                knowledgeBaseId,
                documentTitle,
                documentTitle,
                category,
                null,
                null,
                null,
                buildSourceSnippet(segment.text()),
                extractSimilarity(content),
                cited));
        }
        return sources;
    }

    private String buildSourcesMarkdown(List<Content> contents) {
        List<RagSourceDTO> sources = buildSources(contents, null);
        if (sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n---\n\n## 参考来源\n\n");
        for (int i = 0; i < sources.size(); i++) {
            RagSourceDTO source = sources.get(i);
            sb.append(i + 1).append(". **").append(buildSourceDisplayTitle(source)).append("**");
            if (source.similarity() != null) {
                sb.append("（相似度：").append(String.format(Locale.ROOT, "%.2f", source.similarity())).append("）");
            }
            sb.append("\n\n   > ").append(source.snippet()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildSourceDisplayTitle(RagSourceDTO source) {
        return firstNonBlank(source.sourceName(), source.documentTitle(), "未知知识库");
    }

    private Long extractKnowledgeBaseId(Content content) {
        var metadata = content.textSegment().metadata();
        String docId = metadata.getString("docId");
        if (docId == null || docId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(docId);
        } catch (NumberFormatException e) {
            log.warn("无法解析引用来源知识库ID: docId={}", docId);
            return null;
        }
    }

    private Double extractSimilarity(Content content) {
        Object score = content.metadata().get(ContentMetadata.SCORE);
        if (score instanceof Number number) {
            double v = number.doubleValue();
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                return Math.round(v * 10000.0) / 10000.0;
            }
        }
        // rerank 后的 RERANKED_SCORE
        Object reranked = content.metadata().get(ContentMetadata.RERANKED_SCORE);
        if (reranked instanceof Number number) {
            double v = number.doubleValue();
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                return Math.round(v * 10000.0) / 10000.0;
            }
        }
        return null;
    }

    private String buildSourceSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String snippet = text.replaceAll("\\s+", " ").trim();
        if (snippet.length() <= SOURCE_SNIPPET_MAX_CHARS) {
            return snippet;
        }
        return snippet.substring(0, SOURCE_SNIPPET_MAX_CHARS) + "...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // ========== 流式归一化 / 埋点 ==========

    private Flux<String> instrumentStream(Flux<String> source) {
        if (meterRegistry == null) {
            return source;
        }
        long[] subscribeNanos = new long[1];
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
        return source
            .doOnSubscribe(s -> {
                subscribeNanos[0] = System.nanoTime();
                int active = activeStreams.incrementAndGet();
                meterRegistry.gauge("app.ai.rag.stream.active", activeStreams);
                log.debug("RAG 流式并发数: {}", active);
            })
            .doOnNext(token -> {
                if (token != null && !token.isEmpty() && firstTokenSeen.compareAndSet(false, true)) {
                    meterRegistry.timer("app.ai.rag.stream.first_token_latency")
                        .record(System.nanoTime() - subscribeNanos[0], java.util.concurrent.TimeUnit.NANOSECONDS);
                }
            })
            .doFinally(signal -> {
                activeStreams.decrementAndGet();
                meterRegistry.timer("app.ai.rag.stream.total_latency")
                    .record(System.nanoTime() - subscribeNanos[0], java.util.concurrent.TimeUnit.NANOSECONDS);
            });
    }

    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final reactor.core.Disposable[] disposableRef = new reactor.core.Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }
                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /** augment 结果：检索到的 contents 与注入检索内容后的 chatMessage（供 LLM 生成）。 */
    private record AugmentationOutcome(List<Content> contents, ChatMessage chatMessage) {}
}
