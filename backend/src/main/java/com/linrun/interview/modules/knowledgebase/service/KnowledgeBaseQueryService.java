package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.FluxStreamingBridge;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.PromptSecurityConstants;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.observability.LangfuseSpan;
import com.linrun.interview.common.observability.LangfuseTracer;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.config.ElasticSearchProperties;
import com.linrun.interview.modules.knowledgebase.model.QueryRequest;
import com.linrun.interview.modules.knowledgebase.model.QueryResponse;
import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import com.linrun.interview.modules.knowledgebase.rag.CompositeContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.ContentUtil;
import com.linrun.interview.modules.knowledgebase.rag.CorrectiveRetrievalGrader;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryDecomposer;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.InterviewCompositeQueryTransformer;
import com.linrun.interview.modules.knowledgebase.rag.InterviewIntent;
import com.linrun.interview.modules.knowledgebase.rag.InterviewHybridContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.InterviewElasticsearchContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.InterviewNeo4jContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryRouter;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryTransformer;
import com.linrun.interview.modules.knowledgebase.rag.InterviewReRankingContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.InterviewSqlContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.ProgressAwareContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.ProgressAwareContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper;
import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 知识库查询服务（LangChain4j RetrievalAugmentor 编排版，对齐业界实践）。
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
 * <p>已补齐 业界实现 的 3 个 RAG 编排亮点（取精华弃糟粕）：
 * <ul>
 *   <li><b>多轮历史喂生成</b>：{@code generateAnswer}/{@code streamGenerate} 把历史 {@code ChatMessage}
 *       拼进 {@link ChatRequest#messages()}（System + history + outcome.chatMessage），追问指代不再丢失</li>
 *   <li><b>流式进度回调</b>：{@link InterviewQueryTransformer}/{@link InterviewElasticsearchContentRetriever}/
 *       {@link InterviewReRankingContentAggregator} 接 {@code progressCallback}，SSE data 以
 *       {@code progress:} / {@code reference:} / 普通文本 前缀分流</li>
 *   <li><b>改写结果回写</b>：{@link InterviewQueryTransformer} 改写完用虚拟线程异步回写
 *       {@code rag_chat_messages.transform_content}（弃静态 ApplicationContext 反模式，Spring 注入 repository）</li>
 *   <li><b>意图识别兜底</b>（亮点4）：{@link #answerQuestionStream} 前置 {@link IntentRecognitionService}
 *       判定问题相关性，不相关走 {@link CommonChatService} 通用对话（不检索），由 {@code app.ai.rag.intent-recognition.enabled} 控制开关</li>
 *   <li><b>JSON 容错解析</b>（亮点7）：意图识别结果经 {@link JsonUtil#fixAndParse(String)} 容错解析</li>
 * </ul>
 *
 * <p>Spring AI {@link org.springframework.core.io.Resource} 已全部替换为 LC4j {@link Content}/{@link TextSegment}。
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

    /** SSE 流前缀协议：阶段进度。 */
    static final String PROGRESS_PREFIX = "progress:";
    /** SSE 流前缀协议：引用来源（JSON 数组，复用 {@link RagSourceDTO}）。 */
    static final String REFERENCE_PREFIX = "reference:";
    /** SSE 改写后问题（检索优化结果）。 */
    static final String REWRITTEN_PREFIX = "rewritten:";
    /** SSE 路由策略（JSON：strategy/reasoning）。 */
    static final String ROUTE_PREFIX = "route:";
    /** SSE 交互卡片提示。 */
    static final String CARD_PREFIX = "card:";
    /** SSE 交互卡片选项 JSON。 */
    static final String CARD_CHOICE_PREFIX = "card_choice:";

    private static final ThreadLocal<IntentRecognitionResult> ACTIVE_INTENT = new ThreadLocal<>();

    private final LlmProviderRegistry llmProviderRegistry;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final RestClient restClient;
    private final ElasticSearchProperties elasticSearchProperties;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final RerankService rerankService;
    private final KnowledgeSegmentService segmentService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final String rewriteModel;
    private final int topk;
    private final double minScore;
    private final boolean rerankEnabled;
    private final boolean citationEnabled;
    private final double citationCoverageWeight;
    private final double citationInvalidPenalty;
    private final CitationAnalyzer citationAnalyzer;
    private final int rerankTopN;
    private final KnowledgeBaseQueryProperties.ParentExpand parentExpand;
    private final KnowledgeBaseQueryProperties.Hybrid hybrid;
    private final KnowledgeBaseQueryProperties.Sql sql;
    private final KnowledgeBaseQueryProperties.Routing routing;
    private final KnowledgeBaseQueryProperties.Graph graph;
    private final KnowledgeBaseQueryProperties.Generation generation;
    private final dev.langchain4j.model.input.PromptTemplate cypherPromptTemplate;
    private final dev.langchain4j.model.input.PromptTemplate sqlPromptTemplate;
    private final Driver neo4jDriver;
    private final KnowledgeBaseQueryProperties.IntentRecognition intentRecognition;
    private final MeterRegistry meterRegistry;
    private final RagChatMessageMapper ragChatMessageMapper;
    private final ObjectMapper objectMapper;
    private final IntentRecognitionService intentRecognitionService;
    private final CommonChatService commonChatService;
    private final DataSource dataSource;
    private final KnowledgeBaseDataTableService dataTableService;
    private final RagQueryTraceService traceService;
    private final RagPromptService ragPromptService;
    private final RagCardService ragCardService;
    private final SegmentTextCacheService segmentTextCacheService;
    private final LangfuseTracer langfuseTracer;
    private final PromptTemplate hydePromptTemplate;
    private final boolean hydeEnabled;
    private final int hydeMaxChars;
    private final KnowledgeBaseQueryProperties.Decompose decompose;
    private final KnowledgeBaseQueryProperties.Crag crag;
    private final PromptTemplate decomposePromptTemplate;
    private final PromptTemplate cragPromptTemplate;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            ElasticsearchEmbeddingStore embeddingStore,
            RestClient restClient,
            ElasticSearchProperties elasticSearchProperties,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            RerankService rerankService,
            KnowledgeSegmentService segmentService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            RagChatMessageMapper ragChatMessageMapper,
            ObjectMapper objectMapper,
            IntentRecognitionService intentRecognitionService,
            CommonChatService commonChatService,
            DataSource dataSource,
            KnowledgeBaseDataTableService dataTableService,
            RagQueryTraceService traceService,
            RagPromptService ragPromptService,
            RagCardService ragCardService,
            SegmentTextCacheService segmentTextCacheService,
            LangfuseTracer langfuseTracer,
            @Autowired(required = false) Driver neo4jDriver,
            @Autowired(required = false)
            MeterRegistry meterRegistry) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.embeddingStore = embeddingStore;
        this.restClient = restClient;
        this.elasticSearchProperties = elasticSearchProperties;
        this.listService = listService;
        this.countService = countService;
        this.rerankService = rerankService;
        this.segmentService = segmentService;
        this.ragChatMessageMapper = ragChatMessageMapper;
        this.objectMapper = objectMapper;
        this.intentRecognitionService = intentRecognitionService;
        this.commonChatService = commonChatService;
        this.dataSource = dataSource;
        this.dataTableService = dataTableService;
        this.traceService = traceService;
        this.ragPromptService = ragPromptService;
        this.ragCardService = ragCardService;
        this.segmentTextCacheService = segmentTextCacheService;
        this.langfuseTracer = langfuseTracer;
        this.hydePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getHydePromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.hydeEnabled = queryProperties.getHyde().isEnabled();
        this.hydeMaxChars = queryProperties.getHyde().getMaxChars();
        this.meterRegistry = meterRegistry;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.rewriteModel = queryProperties.getRewrite().getModel();
        this.topk = queryProperties.getSearch().getTopkMedium();
        this.minScore = queryProperties.getSearch().getMinScoreDefault();
        this.rerankEnabled = queryProperties.getRerank().isEnabled();
        this.citationEnabled = queryProperties.getCitation().isEnabled();
        this.citationCoverageWeight = queryProperties.getCitation().getCoverageWeight();
        this.citationInvalidPenalty = queryProperties.getCitation().getInvalidPenalty();
        this.citationAnalyzer = new CitationAnalyzer(citationCoverageWeight, citationInvalidPenalty);
        this.rerankTopN = queryProperties.getRerank().getTopN();
        this.parentExpand = queryProperties.getParentExpand();
        this.hybrid = queryProperties.getHybrid();
        this.sql = queryProperties.getSql();
        this.routing = queryProperties.getRouting();
        this.graph = queryProperties.getGraph();
        this.generation = queryProperties.getGeneration();
        this.cypherPromptTemplate = dev.langchain4j.model.input.PromptTemplate.from(
            resourceLoader.getResource(graph.getCypherPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.sqlPromptTemplate = dev.langchain4j.model.input.PromptTemplate.from(
            resourceLoader.getResource(sql.getPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.neo4jDriver = neo4jDriver;
        this.intentRecognition = queryProperties.getIntentRecognition();
        this.decompose = queryProperties.getDecompose();
        this.crag = queryProperties.getCrag();
        this.decomposePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(decompose.getPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
        this.cragPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(crag.getPromptPath())
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private ChatModel getChatModel() {
        return getRoutingChatModel();
    }

    private ChatModel getRoutingChatModel() {
        return llmProviderRegistry.getChatModelWithModel(null, routing.getModel());
    }

    private ChatModel getRewriteChatModel() {
        return llmProviderRegistry.getChatModelWithModel(null, rewriteModel);
    }

    private ChatModel getDecomposeChatModel() {
        return llmProviderRegistry.getChatModelWithModel(null, decompose.getModel());
    }

    private ChatModel getCragChatModel() {
        return llmProviderRegistry.getChatModelWithModel(null, crag.getModel());
    }

    private StreamingChatModel getStreamingChatModel() {
        return llmProviderRegistry.getStreamingChatModelWithModel(
            null, generation.getStreamingModel());
    }

    /**
     * RAG 评测专用检索入口：只返回检索到的片段、不生成答案，供 Agent 出题工具 / 评测计算 Hit/MRR/NDCG 复用。
     */
    public List<TextSegment> retrieveForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return List.of();
        }
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of(), null, null, null, true);
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

        langfuseTracer.startTrace("rag.query", UserContext.getUserId(), null, question);
        long start = System.nanoTime();
        RagQueryTrace trace = new RagQueryTrace();
        LangfuseSpan retrieveSpan = langfuseTracer.span("rag-retrieve", question);
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of(), null, null, trace);
        List<Content> contents = outcome.contents();
        langfuseTracer.end(retrieveSpan, "retrieved=" + contents.size());
        if (contents.isEmpty()) {
            traceService.save(UserContext.requireUserId(), knowledgeBaseIds, question, trace, List.of(),
                NO_RESULT_RESPONSE, null, List.of(), elapsedMillis(start));
            langfuseTracer.updateTraceOutput(NO_RESULT_RESPONSE);
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        LangfuseSpan generateSpan = langfuseTracer.span("rag-generate", question);
        String answer = generateAnswer(knowledgeBaseIds, question, outcome, List.of());
        langfuseTracer.end(generateSpan, answer);
        langfuseTracer.updateTraceOutput(answer);
        CitationAnalyzer.CitationAnalysis citation = citationEnabled
            ? citationAnalyzer.analyze(answer, contents.size())
            : new CitationAnalyzer.CitationAnalysis(List.of(), List.of(), 0.0d);
        Double confidence = citationEnabled
            ? citationAnalyzer.confidence(
                contents.stream().map(this::extractSimilarity).toList(), citation)
            : null;
        List<RagSourceDTO> sources = buildSources(contents, Set.copyOf(citation.citedIndexes()));
        traceService.save(UserContext.requireUserId(), knowledgeBaseIds, question, trace, sources, answer,
            confidence, citation.invalidIndexes(), elapsedMillis(start));
        return new QueryResponse(answer, primaryKbId, kbNamesStr, sources, confidence, citation.invalidIndexes());
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of(), null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history) {
        return answerQuestionStream(knowledgeBaseIds, question, history, null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文 + 改写回写的 assistantMessageId）。
     *
     * <p>SSE data 前缀协议：
     * <ul>
     *   <li>{@code progress:xxx} —— 阶段进度（优化问题/检索/排序/生成）</li>
     *   <li>{@code reference:[...]} —— 引用来源 JSON 数组（{@link RagSourceDTO}）</li>
     *   <li>无前缀 —— 回答 token（含流末尾的 sourcesMarkdown）</li>
     * </ul>
     *
     * @param assistantMessageId assistant 消息 ID，非空时改写结果异步回写其 transform_content（亮点5）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question,
                                             List<ChatMessage> history, Long assistantMessageId) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        // 在调用线程（tomcat-handler，JwtInterceptor 已注入 ThreadLocal）提前取出 userId。
        // Flux.create 的 sink lambda 跑在 boundedElastic 线程，此时 JwtInterceptor 的
        // afterCompletion 已清除 ThreadLocal，sink 内任何依赖 UserContext 的调用
        // （countService / listService.getKnowledgeBaseNameMap 等）都会抛 UNAUTHORIZED。
        // 因此 sink 内用 setUserId 临时恢复，finally 清理，避免改动所有下游方法签名。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Flux.just("【错误】知识库查询失败：未登录或 token 无效");
        }

        return Flux.<String>create(sink -> {
            try {
                UserContext.setUserId(userId);
                countService.updateQuestionCounts(knowledgeBaseIds);

                // progressCallback 把各阶段进度推到同一 sink（前缀式协议）
                Consumer<String> progressCallback = msg -> {
                    if (!sink.isCancelled()) {
                        sink.next(PROGRESS_PREFIX + msg);
                    }
                };

                // 亮点4：意图识别兜底。判定问题是否与面试 / 技术知识 / 简历 / 求职等相关，
                // 不相关走通用对话兜底（不检索知识库），避免越界问题强行检索导致幻觉。
                // 识别失败/解析失败默认 related=true 走 RAG（兜底不阻断）。
                if (intentRecognition.isEnabled()) {
                    if (intentRecognition.isProgressEnabled() && !sink.isCancelled()) {
                        sink.next(PROGRESS_PREFIX + "正在理解您的问题...");
                    }
                    IntentRecognitionResult intent = recognizeIntent(question, history);
                    if (intent != null && !intent.related()) {
                        log.info("意图识别判定不相关，走通用对话兜底: question='{}', reason={}",
                            question, intent.reason());
                        if (!sink.isCancelled()) {
                            sink.next(PROGRESS_PREFIX + "正在生成回答...");
                        }
                        Flux<String> commonFlux = instrumentStream(
                            normalizeStreamOutput(commonChatService.streamChat(question)));
                        final reactor.core.Disposable[] innerRef = new reactor.core.Disposable[1];
                        innerRef[0] = commonFlux.subscribe(
                            sink::next,
                            sink::error,
                            sink::complete
                        );
                        sink.onCancel(() -> {
                            if (innerRef[0] != null && !innerRef[0].isDisposed()) {
                                innerRef[0].dispose();
                            }
                        });
                        return;
                    }
                    if (intent != null) {
                        ACTIVE_INTENT.set(intent);
                        var cardFlux = ragCardService.maybeInteractionCards(intent);
                        if (cardFlux.isPresent()) {
                            cardFlux.get().subscribe(
                                sink::next,
                                sink::error,
                                sink::complete
                            );
                            return;
                        }
                    }
                }

                long start = System.nanoTime();
                RagQueryTrace trace = new RagQueryTrace();
                AugmentationOutcome outcome = augment(knowledgeBaseIds, question, history, progressCallback,
                    assistantMessageId, trace);
                List<Content> contents = outcome.contents();
                if (contents.isEmpty()) {
                    traceService.save(userId, knowledgeBaseIds, question, trace, List.of(),
                        NO_RESULT_RESPONSE, null, List.of(), elapsedMillis(start));
                    sink.next(NO_RESULT_RESPONSE);
                    sink.complete();
                    return;
                }

                // augment 后推引用来源（reference: 前缀 + RagSourceDTO JSON）
                List<RagSourceDTO> traceSources = buildSources(contents, null);
                emitRewrittenQuestion(sink, trace);
                emitRouteStrategy(sink, trace);
                emitReference(sink, traceSources);

                log.debug("检索到 {} 个相关片段", contents.size());
                Flux<String> responseFlux = streamGenerate(outcome, history);

                log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
                Flux<String> normalizedFlux = normalizeStreamOutput(responseFlux);
                String sourcesMarkdown = buildSourcesMarkdown(contents);
                if (!sourcesMarkdown.isBlank()) {
                    normalizedFlux = normalizedFlux.concatWith(Flux.just(sourcesMarkdown));
                }

                StringBuilder answerBuffer = new StringBuilder();
                Flux<String> finalFlux = instrumentStream(normalizedFlux)
                    .doOnNext(answerBuffer::append)
                    .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                    .doOnComplete(() -> traceService.save(userId, knowledgeBaseIds, question, trace,
                        traceSources, answerBuffer.toString(), null, List.of(), elapsedMillis(start)))
                    .onErrorResume(e -> {
                        log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                        return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                    });

                final reactor.core.Disposable[] innerRef = new reactor.core.Disposable[1];
                innerRef[0] = finalFlux.subscribe(
                    sink::next,
                    sink::error,
                    sink::complete
                );
                // 外层取消时取消内层 LLM token 流，避免回调继续写入已取消的 sink
                sink.onCancel(() -> {
                    if (innerRef[0] != null && !innerRef[0].isDisposed()) {
                        innerRef[0].dispose();
                    }
                });
            } catch (Exception e) {
                log.error("知识库流式问答失败: {}", e.getMessage(), e);
                if (!sink.isCancelled()) {
                    sink.next("【错误】知识库查询失败：" + e.getMessage());
                    sink.complete();
                }
            } finally {
                UserContext.clear();
                ACTIVE_INTENT.remove();
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 调用意图识别服务（LangChain4j Structured Output）。
     *
     * <p>任何异常都返回 null，由调用方按「相关」兜底走 RAG，不阻断主流程。
     */
    private IntentRecognitionResult recognizeIntent(String question, List<ChatMessage> history) {
        try {
            IntentRecognitionResult result = intentRecognitionService.recognize(question, history);
            if (result == null) {
                log.warn("意图识别返回空，按相关兜底走 RAG");
                return null;
            }
            log.debug("意图识别结果: related={}, intent={}, confidence={}, cached={}, reason={}",
                result.related(), result.intent(), result.confidence(), result.cached(), result.reason());
            return result;
        } catch (Exception e) {
            log.warn("意图识别失败，按相关兜底走 RAG: error={}", e.getMessage(), e);
            return null;
        }
    }

    // ========== RetrievalAugmentor 编排 ==========

    /**
     * 构建 RetrievalAugmentor 并执行 augment，返回检索到的 contents 与注入后的 chatMessage。
     *
     * @param progressCallback 进度回调（null 安全），注入改写/检索/rerank 各阶段
     * @param assistantMessageId assistant 消息 ID，非空时改写结果异步回写（亮点5）
     */
    private AugmentationOutcome augment(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history,
                                        Consumer<String> progressCallback, Long assistantMessageId) {
        return augment(knowledgeBaseIds, question, history, progressCallback, assistantMessageId, null, false);
    }

    private AugmentationOutcome augment(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history,
                                        Consumer<String> progressCallback, Long assistantMessageId,
                                        RagQueryTrace trace) {
        return augment(knowledgeBaseIds, question, history, progressCallback, assistantMessageId, trace, false);
    }

    private AugmentationOutcome augment(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history,
                                        Consumer<String> progressCallback, Long assistantMessageId,
                                        RagQueryTrace trace, boolean knowledgeBaseOnly) {
        RetrievalAugmentor augmentor = buildAugmentor(
            knowledgeBaseIds, history, progressCallback, assistantMessageId, trace, knowledgeBaseOnly);
        UserMessage userMessage = UserMessage.from(question);
        Metadata metadata = Metadata.from(userMessage, SESSION_ID_DEFAULT, history);
        long startNanos = System.nanoTime();
        dev.langchain4j.rag.AugmentationResult result =
            augmentor.augment(new dev.langchain4j.rag.AugmentationRequest(userMessage, metadata));
        recordRetrievalMetrics(startNanos, result.contents());
        AugmentationOutcome outcome = new AugmentationOutcome(result.contents(), result.chatMessage());
        // Agentic RAG：CRAG 纠正式检索（rerank 后对 top-N 打分，ambiguous 重检索一次，incorrect 判「知识库无据」防幻觉）
        if (crag.isEnabled() && !knowledgeBaseOnly && !outcome.contents().isEmpty()) {
            outcome = applyCorrectiveRag(knowledgeBaseIds, question, history,
                progressCallback, assistantMessageId, trace, outcome);
        }
        // 生成前发一次"正在生成回答"进度
        if (progressCallback != null) {
            progressCallback.accept("正在生成回答...");
        }
        return outcome;
    }

    /**
     * CRAG 纠正式检索（Agentic RAG）：让小模型对首轮检索片段相对用户问题打分。
     * <ul>
     *   <li>CORRECT：片段可支撑回答 → 原样返回；</li>
     *   <li>AMBIGUOUS：部分相关 → 用 correctedQuery 重检索一次（硬上限 1，防循环），
     *       与首轮片段合并去重后重新注入到「原始问题」的消息里（生成仍回答用户真实问题）；</li>
     *   <li>INCORRECT：全部无关 → 返回空片段，主链路走「未检索到相关信息」，避免用无关上下文强答产生幻觉。</li>
     * </ul>
     * 打分/重检索失败一律按 CORRECT 兜底，不阻断主链路。
     */
    private AugmentationOutcome applyCorrectiveRag(List<Long> knowledgeBaseIds, String question,
                                                   List<ChatMessage> history, Consumer<String> progressCallback,
                                                   Long assistantMessageId, RagQueryTrace trace,
                                                   AugmentationOutcome outcome) {
        try {
            if (progressCallback != null) {
                progressCallback.accept("正在校验检索结果...");
            }
            CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(
                getCragChatModel(), cragPromptTemplate, crag.getGradeTopN(), crag.getSnippetMaxChars());
            CorrectiveRetrievalGrader.GradeResult gr = grader.grade(question, outcome.contents());
            switch (gr.grade()) {
                case CORRECT -> {
                    if (trace != null) {
                        trace.crag(gr.grade().name(), "keep");
                    }
                    return outcome;
                }
                case INCORRECT -> {
                    if (trace != null) {
                        trace.crag(gr.grade().name(), "drop");
                    }
                    log.info("[CRAG] 片段全部无关，判定知识库无据，返回空片段防幻觉: question='{}'", question);
                    return new AugmentationOutcome(List.of(), outcome.chatMessage());
                }
                case AMBIGUOUS -> {
                    String corrected = gr.correctedQuery();
                    if (corrected == null || corrected.isBlank() || corrected.equals(question)) {
                        if (trace != null) {
                            trace.crag(gr.grade().name(), "keep");
                        }
                        return outcome;
                    }
                    log.info("[CRAG] 片段部分相关，用纠正查询重检索一次: origin='{}', corrected='{}'",
                        question, corrected);
                    List<Content> corrctedContents = retrieveContentsOnly(
                        knowledgeBaseIds, corrected, history, progressCallback, assistantMessageId, trace);
                    List<Content> merged = mergeDedupContents(outcome.contents(), corrctedContents);
                    if (trace != null) {
                        trace.crag(gr.grade().name(), "re-retrieve:" + corrected);
                    }
                    ChatMessage injected = new DefaultContentInjector()
                        .inject(merged, UserMessage.from(question));
                    return new AugmentationOutcome(merged, injected);
                }
                default -> {
                    return outcome;
                }
            }
        } catch (Exception e) {
            log.warn("[CRAG] 纠正式检索失败，按原检索结果兜底: {}", e.getMessage(), e);
            return outcome;
        }
    }

    /**
     * 仅检索不注入：构建一次性 augmentor 取指定 query 的检索片段（CRAG 重检索复用，
     * 不再走 CRAG 自身，天然防递归）。
     */
    private List<Content> retrieveContentsOnly(List<Long> knowledgeBaseIds, String query,
                                               List<ChatMessage> history, Consumer<String> progressCallback,
                                               Long assistantMessageId, RagQueryTrace trace) {
        RetrievalAugmentor augmentor = buildAugmentor(
            knowledgeBaseIds, history, progressCallback, assistantMessageId, trace, false);
        UserMessage userMessage = UserMessage.from(query);
        Metadata metadata = Metadata.from(userMessage, SESSION_ID_DEFAULT, history);
        return augmentor.augment(new dev.langchain4j.rag.AugmentationRequest(userMessage, metadata)).contents();
    }

    /** 合并两轮检索片段并按 chunk 文本去重（保序，首轮优先）。 */
    private List<Content> mergeDedupContents(List<Content> first, List<Content> second) {
        List<Content> merged = new ArrayList<>(first);
        Set<String> seen = new java.util.HashSet<>();
        for (Content c : first) {
            seen.add(c.textSegment().text());
        }
        for (Content c : second) {
            if (seen.add(c.textSegment().text())) {
                merged.add(c);
            }
        }
        return merged;
    }

    /** Grafana 看板依赖：app.ai.rag.retrieval.latency（P99/P95/P50）+ requests（按 hit 分组）。 */
    private void recordRetrievalMetrics(long startNanos, List<Content> contents) {
        if (meterRegistry == null) {
            return;
        }
        boolean hit = contents != null && !contents.isEmpty();
        meterRegistry.timer("app.ai.rag.retrieval.latency")
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        meterRegistry.counter("app.ai.rag.retrieval.requests", "hit", String.valueOf(hit)).increment();
    }

    private RetrievalAugmentor buildAugmentor(List<Long> knowledgeBaseIds, List<ChatMessage> history,
                                              Consumer<String> progressCallback, Long assistantMessageId) {
        return buildAugmentor(knowledgeBaseIds, history, progressCallback, assistantMessageId, null, false);
    }

    private RetrievalAugmentor buildAugmentor(List<Long> knowledgeBaseIds, List<ChatMessage> history,
                                              Consumer<String> progressCallback, Long assistantMessageId,
                                              RagQueryTrace trace, boolean knowledgeBaseOnly) {
        Long userId = UserContext.requireUserId();
        List<InterviewElasticsearchContentRetriever> esRetrievers =
            buildElasticsearchRetrievers(knowledgeBaseIds, null, trace, userId);
        List<ContentRetriever> esRouterRetrievers = esRetrievers.stream()
            .map(r -> ProgressAwareContentRetriever.wrap(
                r, progressCallback, ProgressAwareContentRetriever.Kind.ES))
            .toList();
        ContentRetriever esFallback = esRouterRetrievers.size() == 1
            ? esRouterRetrievers.getFirst()
            : new CompositeContentRetriever(new ArrayList<>(esRouterRetrievers));
        InterviewSqlContentRetriever sqlRetriever = sql.isEnabled() && !knowledgeBaseOnly
            ? new InterviewSqlContentRetriever(dataSource, getChatModel(), esFallback,
                dataTableService.databaseStructure(userId),
                dataTableService.allowedDynamicTables(userId),
                sql.getQueryTimeoutSeconds(),
                sql.getMaxRows(),
                userId,
                sqlPromptTemplate)
            : null;
        ContentRetriever routedSql = sqlRetriever == null ? null
            : ProgressAwareContentRetriever.wrap(
                sqlRetriever, progressCallback, ProgressAwareContentRetriever.Kind.SQL);
        InterviewNeo4jContentRetriever neo4jRetriever = buildNeo4jRetriever(esFallback, knowledgeBaseOnly, trace);
        ContentRetriever routedNeo4j = neo4jRetriever == null ? null
            : ProgressAwareContentRetriever.wrap(
                neo4jRetriever, progressCallback, ProgressAwareContentRetriever.Kind.NEO4J);
        InterviewQueryTransformer rewriteTransformer = new InterviewQueryTransformer(
            getRewriteChatModel(), rewritePromptTemplate, rewriteEnabled, progressCallback,
            assistantMessageId, ragChatMessageMapper, trace);
        InterviewCompositeQueryTransformer transformer = new InterviewCompositeQueryTransformer(
            rewriteTransformer, getRewriteChatModel(), hydePromptTemplate, hydeEnabled, hydeMaxChars);
        // Agentic RAG：复杂问题（多跳/对比/综合）先分解成子查询，与原 query 并行检索后 RRF 融合去重。
        // 规则预筛 + LLM 二次判定，简单问题零额外调用；失败降级原改写链，不阻断检索。
        // 评测检索路径（knowledgeBaseOnly）不分解，保证 Hit/MRR/NDCG 口径稳定可复现。
        QueryTransformer queryTransformer = decompose.isEnabled() && !knowledgeBaseOnly
            ? new InterviewQueryDecomposer(transformer, getDecomposeChatModel(), decomposePromptTemplate,
                decompose.getMaxSubQueries(), progressCallback, trace)
            : transformer;
        InterviewReRankingContentAggregator rerankAggregator = rerankEnabled && rerankService.isEnabled()
            ? InterviewReRankingContentAggregator.builder()
                .scoringModel(rerankService)
                .maxResults(rerankTopN > 0 ? rerankTopN : null)
                .querySelector(qtc -> qtc.keySet().iterator().next())
                .progressCallback(null)
                .trace(trace)
                .build()
            : null;
        ContentInjector contentInjector = new DefaultContentInjector();
        ContentAggregator hybridAggregator = sql.isEnabled() && !knowledgeBaseOnly
            ? new InterviewHybridContentAggregator(rerankAggregator)
            : rerankAggregator;
        ContentAggregator finalAggregator = ProgressAwareContentAggregator.wrap(hybridAggregator, progressCallback);

        InterviewIntent intentHint = ACTIVE_INTENT.get() != null
            ? ACTIVE_INTENT.get().resolvedIntent()
            : null;
        InterviewQueryRouter queryRouter = knowledgeBaseOnly
            ? InterviewQueryRouter.builder()
                .elasticsearchRetrievers(esRouterRetrievers)
                .intentHint(intentHint)
                .build()
            : InterviewQueryRouter.builder()
                .elasticsearchRetrievers(esRouterRetrievers)
                .sqlRetriever(routedSql)
                .neo4jRetriever(routedNeo4j)
                .chatModel(getRoutingChatModel())
                .enabled(isRouterEnabled(sqlRetriever, neo4jRetriever))
                .progressCallback(progressCallback)
                .trace(trace)
                .intentHint(intentHint)
                .build();
        DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder builder = DefaultRetrievalAugmentor.builder()
            .queryRouter(queryRouter)
            .queryTransformer(queryTransformer)
            .contentInjector(contentInjector);
        if (finalAggregator != null) {
            builder.contentAggregator(finalAggregator);
        }
        return builder.build();
    }

    private List<InterviewElasticsearchContentRetriever> buildElasticsearchRetrievers(
        List<Long> knowledgeBaseIds,
        Consumer<String> progressCallback,
        RagQueryTrace trace,
        Long userId) {
        if (hybrid.isDualChannel()) {
            return List.of(
                createElasticsearchRetriever(knowledgeBaseIds, progressCallback, trace, userId, "vector"),
                createElasticsearchRetriever(knowledgeBaseIds, progressCallback, trace, userId, "full_text"));
        }
        return List.of(createElasticsearchRetriever(knowledgeBaseIds, progressCallback, trace, userId, null));
    }

    private InterviewElasticsearchContentRetriever createElasticsearchRetriever(
        List<Long> knowledgeBaseIds,
        Consumer<String> progressCallback,
        RagQueryTrace trace,
        Long userId,
        String forcedSearchMode) {
        return new InterviewElasticsearchContentRetriever(
            embeddingStore, llmProviderRegistry.getDefaultEmbeddingModel(), topk, minScore,
            knowledgeBaseIds, segmentService, parentExpand, hybrid, progressCallback, trace,
            restClient, elasticSearchProperties.getIndexName(), objectMapper,
            forcedSearchMode, userId, segmentTextCacheService);
    }

    private void emitRewrittenQuestion(reactor.core.publisher.FluxSink<String> sink, RagQueryTrace trace) {
        if (sink.isCancelled() || trace == null) {
            return;
        }
        String rewritten = trace.rewrittenQuestion();
        if (rewritten != null && !rewritten.isBlank()) {
            sink.next(REWRITTEN_PREFIX + rewritten);
        }
    }

    private void emitRouteStrategy(reactor.core.publisher.FluxSink<String> sink, RagQueryTrace trace) {
        if (sink.isCancelled() || trace == null || trace.routeStrategy() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                "strategy", trace.routeStrategy(),
                "reasoning", trace.routeReasoning() == null ? "" : trace.routeReasoning()));
            sink.next(ROUTE_PREFIX + json);
        } catch (Exception e) {
            log.warn("路由策略 SSE 序列化失败: {}", e.getMessage(), e);
        }
    }

    private InterviewNeo4jContentRetriever buildNeo4jRetriever(
        ContentRetriever fallbackRetriever, boolean knowledgeBaseOnly, RagQueryTrace trace) {
        if (knowledgeBaseOnly || !graph.isEnabled() || neo4jDriver == null) {
            return null;
        }
        try {
            return InterviewNeo4jContentRetriever.builder()
                .graph(Neo4jGraph.builder().driver(neo4jDriver).build())
                .chatModel(getRoutingChatModel())
                .promptTemplate(cypherPromptTemplate)
                .fallbackRetriever(fallbackRetriever)
                .trace(trace)
                .build();
        } catch (Exception e) {
            log.warn("[KnowledgeBaseQueryService] Neo4j 检索器创建失败，跳过图检索: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean isRouterEnabled(InterviewSqlContentRetriever sqlRetriever,
                                    InterviewNeo4jContentRetriever neo4jRetriever) {
        if (sqlRetriever == null && neo4jRetriever == null) {
            return false;
        }
        if (sqlRetriever != null) {
            return sql.isRouterEnabled();
        }
        return graph.isEnabled();
    }

    // ========== 生成 ==========

    private String generateAnswer(List<Long> knowledgeBaseIds, String question, AugmentationOutcome outcome,
                                  List<ChatMessage> history) {
        try {
            String answer = getChatModel().chat(buildGenerateRequest(outcome, history))
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

    private Flux<String> streamGenerate(AugmentationOutcome outcome, List<ChatMessage> history) {
        return FluxStreamingBridge.stream(getStreamingChatModel(), buildGenerateRequest(outcome, history));
    }

    /**
     * 构造生成请求 messages：SystemMessage + 历史 ChatMessage + 注入检索内容后的 outcome.chatMessage()。
     * （亮点1：把多轮历史喂给生成模型，追问指代不再丢失）
     */
    private ChatRequest buildGenerateRequest(AugmentationOutcome outcome, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>(2 + (history == null ? 0 : history.size()));
        messages.add(SystemMessage.from(buildSystemPrompt()));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(outcome.chatMessage());
        return ChatRequest.builder().messages(messages).build();
    }

    private String buildSystemPrompt() {
        IntentRecognitionResult intent = ACTIVE_INTENT.get();
        InterviewIntent resolved = intent != null ? intent.resolvedIntent() : InterviewIntent.TECH_KB;
        String prompt = ragPromptService.getPrompt(resolved) + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
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
            // 图谱检索命中（skipRerank 标记）没有 docId/fileName 元数据，标为「知识图谱」而非「未知知识库」
            String noKbTitle = ContentUtil.isSkipRerank(content) ? "知识图谱" : "未知知识库";
            String fallbackTitle = knowledgeBaseId == null
                ? noKbTitle : nameMap.getOrDefault(knowledgeBaseId, noKbTitle);
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
                // rerank 相关性分（0~1，模型相对分，非余弦相似度），命名「相关度」避免误读
                sb.append("（相关度 ").append(String.format(Locale.ROOT, "%.2f", source.similarity())).append("）");
            }
            sb.append("\n\n   > ").append(source.snippet()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 把检索来源序列化为 {@code reference:} 前缀事件推到流（亮点2，复用 {@link RagSourceDTO}）。
     */
    private void emitReference(reactor.core.publisher.FluxSink<String> sink, List<RagSourceDTO> sources) {
        try {
            if (sources.isEmpty()) {
                return;
            }
            String json = objectMapper.writeValueAsString(sources);
            if (!sink.isCancelled()) {
                sink.next(REFERENCE_PREFIX + json);
            }
        } catch (Exception e) {
            log.warn("序列化引用来源失败，跳过 reference 事件: {}", e.getMessage());
        }
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
        // 去掉 Markdown 语法（标题 #、加粗/斜体 */_、行内代码 `、引用 >），
        // 否则 snippet 在前端「参考来源」引用块里会被 react-markdown 渲染成巨大标题/斜体
        String snippet = text
            .replaceAll("(?m)^#{1,6}\\s*", "")
            .replaceAll("[*_`>]", "")
            .replaceAll("\\s+", " ")
            .trim();
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

    private long elapsedMillis(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** augment 结果：检索到的 contents 与注入检索内容后的 chatMessage（供 LLM 生成）。 */
    private record AugmentationOutcome(List<Content> contents, ChatMessage chatMessage) {}
}
