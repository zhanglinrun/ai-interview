package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.FluxStreamingBridge;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.PromptSecurityConstants;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.model.QueryRequest;
import com.linrun.interview.modules.knowledgebase.model.QueryResponse;
import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionService;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.InterviewHybridContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.InterviewElasticsearchContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryRouter;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryTransformer;
import com.linrun.interview.modules.knowledgebase.rag.InterviewReRankingContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.InterviewSqlContentRetriever;
import com.linrun.interview.modules.knowledgebase.repository.RagChatMessageRepository;
import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
 * <p>已补齐 know-engine 的 3 个 RAG 编排亮点（取精华弃糟粕）：
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

    private final LlmProviderRegistry llmProviderRegistry;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final RerankService rerankService;
    private final KnowledgeSegmentService segmentService;
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
    private final KnowledgeBaseQueryProperties.ParentExpand parentExpand;
    private final KnowledgeBaseQueryProperties.Hybrid hybrid;
    private final KnowledgeBaseQueryProperties.Sql sql;
    private final KnowledgeBaseQueryProperties.IntentRecognition intentRecognition;
    private final MeterRegistry meterRegistry;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final ObjectMapper objectMapper;
    private final IntentRecognitionService intentRecognitionService;
    private final CommonChatService commonChatService;
    private final DataSource dataSource;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            ElasticsearchEmbeddingStore embeddingStore,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            RerankService rerankService,
            KnowledgeSegmentService segmentService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            RagChatMessageRepository ragChatMessageRepository,
            ObjectMapper objectMapper,
            IntentRecognitionService intentRecognitionService,
            CommonChatService commonChatService,
            DataSource dataSource,
            @Autowired(required = false)
            MeterRegistry meterRegistry) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.embeddingStore = embeddingStore;
        this.listService = listService;
        this.countService = countService;
        this.rerankService = rerankService;
        this.segmentService = segmentService;
        this.ragChatMessageRepository = ragChatMessageRepository;
        this.objectMapper = objectMapper;
        this.intentRecognitionService = intentRecognitionService;
        this.commonChatService = commonChatService;
        this.dataSource = dataSource;
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
        this.parentExpand = queryProperties.getParentExpand();
        this.hybrid = queryProperties.getHybrid();
        this.sql = queryProperties.getSql();
        this.intentRecognition = queryProperties.getIntentRecognition();
    }

    private ChatModel getChatModel() {
        return llmProviderRegistry.getDefaultChatModel();
    }

    private StreamingChatModel getStreamingChatModel() {
        return llmProviderRegistry.getDefaultStreamingChatModel();
    }

    /**
     * RAG 评测专用检索入口：只返回检索到的片段、不生成答案，供 Agent 出题工具 / 评测计算 Hit/MRR/NDCG 复用。
     */
    public List<TextSegment> retrieveForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return List.of();
        }
        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of(), null, null, true);
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

        AugmentationOutcome outcome = augment(knowledgeBaseIds, question, List.of(), null, null);
        List<Content> contents = outcome.contents();
        if (contents.isEmpty()) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        String answer = generateAnswer(knowledgeBaseIds, question, outcome, List.of());
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
                    IntentRecognitionResult intent = recognizeIntent(question);
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
                }

                AugmentationOutcome outcome = augment(knowledgeBaseIds, question, history, progressCallback,
                    assistantMessageId);
                List<Content> contents = outcome.contents();
                if (contents.isEmpty()) {
                    sink.next(NO_RESULT_RESPONSE);
                    sink.complete();
                    return;
                }

                // augment 后推引用来源（reference: 前缀 + RagSourceDTO JSON）
                emitReference(sink, contents);

                log.debug("检索到 {} 个相关片段", contents.size());
                Flux<String> responseFlux = streamGenerate(outcome, history);

                log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
                Flux<String> normalizedFlux = normalizeStreamOutput(responseFlux);
                String sourcesMarkdown = buildSourcesMarkdown(contents);
                if (!sourcesMarkdown.isBlank()) {
                    normalizedFlux = normalizedFlux.concatWith(Flux.just(sourcesMarkdown));
                }

                Flux<String> finalFlux = instrumentStream(normalizedFlux)
                    .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
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
                // 清理 sink 线程临时恢复的 ThreadLocal，避免 boundedElastic 线程池复用后串号
                UserContext.clear();
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 调用意图识别服务并容错解析结果（亮点4）。
     *
     * <p>用 {@link IntentRecognitionService#recognize(String)} 拿原始 JSON 字符串，经
     * {@link JsonUtil#fixAndParse(String)} 容错解析为 {@link IntentRecognitionResult}。
     * 任何异常（LLM 调用失败 / 解析失败 / 字段缺失）都返回 null，由调用方按"相关"兜底走 RAG，
     * 不阻断主流程。
     */
    private IntentRecognitionResult recognizeIntent(String question) {
        try {
            String json = intentRecognitionService.recognize(question);
            if (json == null || json.isBlank()) {
                log.warn("意图识别返回空，按相关兜底走 RAG");
                return null;
            }
            var node = JsonUtil.fixAndParse(json);
            if (node == null || node.isMissingNode() || node.isNull()) {
                log.warn("意图识别解析为空节点，按相关兜底走 RAG: raw={}", json);
                return null;
            }
            var relatedNode = node.get("related");
            if (relatedNode == null || !relatedNode.isBoolean()) {
                log.warn("意图识别缺少 related 布尔字段，按相关兜底走 RAG: raw={}", json);
                return null;
            }
            boolean related = relatedNode.asBoolean();
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
            log.debug("意图识别结果: related={}, reason={}", related, reason);
            return new IntentRecognitionResult(related, reason);
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
        return augment(knowledgeBaseIds, question, history, progressCallback, assistantMessageId, false);
    }

    private AugmentationOutcome augment(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history,
                                        Consumer<String> progressCallback, Long assistantMessageId,
                                        boolean knowledgeBaseOnly) {
        RetrievalAugmentor augmentor = buildAugmentor(
            knowledgeBaseIds, history, progressCallback, assistantMessageId, knowledgeBaseOnly);
        UserMessage userMessage = UserMessage.from(question);
        Metadata metadata = Metadata.from(userMessage, SESSION_ID_DEFAULT, history);
        dev.langchain4j.rag.AugmentationResult result =
            augmentor.augment(new dev.langchain4j.rag.AugmentationRequest(userMessage, metadata));
        // 生成前发一次"正在生成回答"进度
        if (progressCallback != null) {
            progressCallback.accept("正在生成回答...");
        }
        return new AugmentationOutcome(result.contents(), result.chatMessage());
    }

    private RetrievalAugmentor buildAugmentor(List<Long> knowledgeBaseIds, List<ChatMessage> history,
                                              Consumer<String> progressCallback, Long assistantMessageId) {
        return buildAugmentor(knowledgeBaseIds, history, progressCallback, assistantMessageId, false);
    }

    private RetrievalAugmentor buildAugmentor(List<Long> knowledgeBaseIds, List<ChatMessage> history,
                                              Consumer<String> progressCallback, Long assistantMessageId,
                                              boolean knowledgeBaseOnly) {
        InterviewElasticsearchContentRetriever retriever = new InterviewElasticsearchContentRetriever(
            embeddingStore, llmProviderRegistry.getDefaultEmbeddingModel(), topk, minScore,
            knowledgeBaseIds, segmentService, parentExpand, hybrid, progressCallback);
        InterviewSqlContentRetriever sqlRetriever = sql.isEnabled() && !knowledgeBaseOnly
            ? new InterviewSqlContentRetriever(dataSource, getChatModel(), retriever)
            : null;
        InterviewQueryTransformer transformer = new InterviewQueryTransformer(
            getChatModel(), rewritePromptTemplate, rewriteEnabled, progressCallback,
            assistantMessageId, ragChatMessageRepository);
        InterviewReRankingContentAggregator aggregator = rerankEnabled && rerankService.isEnabled()
            ? InterviewReRankingContentAggregator.builder()
                .scoringModel(rerankService)
                .maxResults(rerankTopN > 0 ? rerankTopN : null)
                .querySelector(qtc -> qtc.keySet().iterator().next())
                .progressCallback(progressCallback)
                .build()
            : null;
        ContentInjector contentInjector = new DefaultContentInjector();
        var finalAggregator = sql.isEnabled() && !knowledgeBaseOnly
            ? new InterviewHybridContentAggregator(aggregator)
            : aggregator;

        DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder builder = DefaultRetrievalAugmentor.builder()
            .queryRouter(knowledgeBaseOnly
                ? new InterviewQueryRouter(retriever)
                : new InterviewQueryRouter(retriever, sqlRetriever, getChatModel(),
                    sql.isEnabled() && sql.isRouterEnabled(), progressCallback))
            .queryTransformer(transformer)
            .contentInjector(contentInjector);
        if (finalAggregator != null) {
            builder.contentAggregator(finalAggregator);
        }
        return builder.build();
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

    /**
     * 把检索来源序列化为 {@code reference:} 前缀事件推到流（亮点2，复用 {@link RagSourceDTO}）。
     */
    private void emitReference(reactor.core.publisher.FluxSink<String> sink, List<Content> contents) {
        try {
            List<RagSourceDTO> sources = buildSources(contents, null);
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
