package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagSourceDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;
    private static final int SOURCE_SNIPPET_MAX_CHARS = 220;
    /** 追加到 system prompt 的引用标注指令，要求模型用 [n] 把陈述挂到具体来源。 */
    private static final String CITATION_INSTRUCTION = """

# Citation (引用标注)
上下文每个片段前都标有 [n] 编号（从 [1] 开始）。回答时：
- 每一条基于检索内容的客观陈述，都要在相关句末用方括号标注其来源编号，例如 [1]、[2]；一条陈述引用多个来源时写成 [1][2]。
- 只能使用上下文中真实出现的编号，严禁编造不存在的编号。
- 与检索内容无关的过渡或总结性语句可不标注。
""";
    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final RerankService rerankService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    private final boolean hybridEnabled;
    private final boolean rerankEnabled;
    private final boolean citationEnabled;
    private final double citationCoverageWeight;
    private final double citationInvalidPenalty;
    private final CitationAnalyzer citationAnalyzer;
    private final PromptTemplate hydePromptTemplate;
    private final boolean hydeEnabled;
    private final int hydeMaxChars;
    private final long hydeTimeoutMs;
    private final boolean fusionEnabled;
    private final int fusionPerQueryTopK;
    private final int fusionRrfK;
    private final int fusionFinalTopK;
    private final boolean parentExpandEnabled;
    private final int parentExpandMaxChars;
    private final int parentExpandMaxSiblings;
    private final MeterRegistry meterRegistry;
    private final java.util.concurrent.atomic.AtomicInteger activeStreams =
        new java.util.concurrent.atomic.AtomicInteger(0);

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            RerankService rerankService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            MeterRegistry meterRegistry) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.rerankService = rerankService;
        this.meterRegistry = meterRegistry;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.hydePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getHydePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
        this.hybridEnabled = queryProperties.getHybrid().isEnabled();
        this.rerankEnabled = queryProperties.getRerank().isEnabled();
        this.citationEnabled = queryProperties.getCitation().isEnabled();
        this.citationCoverageWeight = queryProperties.getCitation().getCoverageWeight();
        this.citationInvalidPenalty = queryProperties.getCitation().getInvalidPenalty();
        this.citationAnalyzer = new CitationAnalyzer(citationCoverageWeight, citationInvalidPenalty);
        this.hydeEnabled = queryProperties.getHyde().isEnabled();
        this.hydeMaxChars = queryProperties.getHyde().getMaxChars();
        this.hydeTimeoutMs = queryProperties.getHyde().getTimeoutMs();
        this.fusionEnabled = queryProperties.getFusion().isEnabled();
        this.fusionPerQueryTopK = queryProperties.getFusion().getPerQueryTopK();
        this.fusionRrfK = queryProperties.getFusion().getRrfK();
        this.fusionFinalTopK = queryProperties.getFusion().getFinalTopK();
        this.parentExpandEnabled = queryProperties.getParentExpand().isEnabled();
        this.parentExpandMaxChars = queryProperties.getParentExpand().getMaxChars();
        this.parentExpandMaxSiblings = queryProperties.getParentExpand().getMaxSiblings();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        return generateAnswer(knowledgeBaseIds, question, relevantDocs);
    }

    /**
     * RAG 评测专用问答入口：复用真实检索和生成链路，但不更新业务计数。
     */
    public String answerQuestionForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }
        return generateAnswer(knowledgeBaseIds, question, relevantDocs);
    }

    /**
     * RAG 评测专用检索入口：复用真实检索链路（rewrite / HyDE / 多路融合 / rerank），
     * 只返回检索到的文档、不生成答案，供评测计算 Hit/MRR/NDCG 等检索指标。
     * <p>fusion / HyDE 是否生效由构造期开关决定，评测可通过反射切换 {@code fusionEnabled} /
     * {@code hydeEnabled} 字段对比不同档位；调用方负责恢复原值。
     */
    public List<Document> retrieveForEvaluation(List<Long> knowledgeBaseIds, String question) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return List.of();
        }
        QueryContext queryContext = buildQueryContext(question, List.of());
        return retrieveRelevantDocs(queryContext, knowledgeBaseIds);
    }

    private String generateAnswer(List<Long> knowledgeBaseIds, String question, List<Document> relevantDocs) {
        String context = buildNumberedContext(relevantDocs);

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        String prompt = systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        if (citationEnabled) {
            prompt += CITATION_INSTRUCTION;
        }
        return prompt;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 构建喂给模型的上下文。启用引用溯源时，给每个片段前加 [n] 编号（从 1 开始），
     * 配合 system prompt 里的引用指令，让模型把陈述挂到具体来源；关闭时退回原始拼接。
     */
    private String buildNumberedContext(List<Document> docs) {
        // small-to-big：开启时把命中 chunk 扩展为同段聚合的更大上下文喂给 LLM，命中与来源不变
        List<String> texts = parentExpandEnabled
            ? docs.stream().map(this::expandTextForContext).toList()
            : docs.stream().map(Document::getText).toList();
        if (!citationEnabled) {
            return texts.stream().collect(Collectors.joining("\n\n---\n\n"));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append("[").append(i + 1).append("] ").append(texts.get(i));
        }
        return sb.toString();
    }

    /**
     * Small-to-big：把命中 chunk 扩展为同段聚合的更大上下文喂给 LLM。
     * 检索命中与来源列表不变，只扩展上下文文本；扩展失败回退原 chunk。
     */
    private String expandTextForContext(Document doc) {
        try {
            return vectorService.expandChunkWithSiblings(doc, parentExpandMaxChars, parentExpandMaxSiblings);
        } catch (Exception e) {
            log.warn("small-to-big 扩展失败，使用原 chunk: {}", e.getMessage());
            return doc.getText();
        }
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds();
        String question = request.question();

        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return new QueryResponse(NO_RESULT_RESPONSE, null, "", List.of(), null, List.of());
        }

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(knowledgeBaseIds);
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = knowledgeBaseIds.getFirst();

        if (normalizeQuestion(question).isBlank()) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKbId, kbNamesStr, List.of(), null, List.of());
        }

        String answer = generateAnswer(knowledgeBaseIds, question, relevantDocs);
        CitationAnalyzer.CitationAnalysis citation = citationEnabled
            ? citationAnalyzer.analyze(answer, relevantDocs.size())
            : new CitationAnalyzer.CitationAnalysis(List.of(), List.of(), 0.0d);
        Double confidence = citationEnabled
            ? citationAnalyzer.confidence(
                relevantDocs.stream().map(this::extractSimilarity).toList(),
                citation)
            : null;
        List<RagSourceDTO> sources = buildSources(relevantDocs, Set.copyOf(citation.citedIndexes()));
        return new QueryResponse(answer, primaryKbId, kbNamesStr, sources, confidence, citation.invalidIndexes());
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文（启用引用溯源时按 [n] 编号）
            String context = buildNumberedContext(relevantDocs);

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            Flux<String> normalizedFlux = normalizeStreamOutput(responseFlux);
            String sourcesMarkdown = buildSourcesMarkdown(relevantDocs);
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

    /**
     * 为流式输出埋点：并发数 gauge、首字延迟、端到端耗时。
     * 首字延迟以"订阅到第一个非空 token"为口径，是流式体验的核心指标。
     */
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
                        .record(System.nanoTime() - subscribeNanos[0],
                            java.util.concurrent.TimeUnit.NANOSECONDS);
                }
            })
            .doFinally(signal -> {
                activeStreams.decrementAndGet();
                meterRegistry.timer("app.ai.rag.stream.total_latency")
                    .record(System.nanoTime() - subscribeNanos[0],
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            });
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // HyDE：让 LLM 生成假设性答案，作为额外一路检索锚点（多路融合开启时使用）
        String hypothetical = generateHypotheticalDocument(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), hypothetical, searchParams);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    检索：多路召回融合（开启）或串行短路（关闭，历史行为）
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        if (fusionEnabled) {
            return retrieveWithFusion(queryContext, knowledgeBaseIds);
        }
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = searchOne(candidateQuery, queryContext, knowledgeBaseIds);
            log.info("检索候选 query='{}'，混合检索命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                List<Document> ranked = rerankIfEnabled(candidateQuery, docs);
                annotateFinalScore(ranked);
                return ranked;
            }
        }
        return List.of();
    }

    /**
     * 多路召回 + 跨路 RRF 融合：原问题 / rewrite / HyDE 各做一次混合检索，
     * 融合后统一重排；rerank 用原问题作为相关性参照。
     */
    private List<Document> retrieveWithFusion(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        List<List<Document>> routes = new ArrayList<>();
        List<String> queries = new ArrayList<>(queryContext.candidateQueries());
        if (queryContext.hypothetical() != null && !queryContext.hypothetical().isBlank()) {
            queries.add(queryContext.hypothetical());
        }
        for (String candidateQuery : queries) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = searchOneWithTopK(candidateQuery, queryContext, knowledgeBaseIds, fusionPerQueryTopK);
            log.info("多路召回候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            routes.add(docs);
        }
        List<Document> fused = MultiQueryRrfFuser.fuse(routes, fusionRrfK, fusionFinalTopK);
        log.info("多路召回融合完成: 路数={}, 融合后 {} 条", routes.size(), fused.size());
        if (!hasEffectiveHit(fused)) {
            return List.of();
        }
        // 融合器已把跨路最大相似度写进 final_score；重排开启时用 rerank 分覆盖
        List<Document> ranked = rerankIfEnabled(queryContext.originalQuestion(), fused);
        if (rerankEnabled && rerankService.isEnabled()) {
            annotateFinalScore(ranked);
        }
        return ranked;
    }

    /** 单路检索，topK 取自问题长度分档。 */
    private List<Document> searchOne(String query, QueryContext queryContext, List<Long> knowledgeBaseIds) {
        return searchOneWithTopK(query, queryContext, knowledgeBaseIds, queryContext.searchParams().topK());
    }

    /** 单路检索，topK 可指定（多路融合时每路用 perQueryTopK）。 */
    private List<Document> searchOneWithTopK(String query, QueryContext queryContext,
                                             List<Long> knowledgeBaseIds, int topK) {
        return hybridEnabled
            ? vectorService.hybridSearch(query, knowledgeBaseIds, topK, queryContext.searchParams().minScore())
            : vectorService.similaritySearch(query, knowledgeBaseIds, topK, queryContext.searchParams().minScore());
    }

    /**
     * 若重排可用，对融合候选做精排取 topN；否则原样返回融合结果。
     * 任何重排异常都已在 RerankService 内部安全降级。
     */
    private List<Document> rerankIfEnabled(String query, List<Document> docs) {
        if (!rerankEnabled || !rerankService.isEnabled()) {
            return docs;
        }
        List<Document> reranked = rerankService.rerank(query, docs);
        log.info("重排完成: 融合候选 {} -> 精排保留 {}", docs.size(), reranked.size());
        return reranked;
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

    /**
     * HyDE（假设性文档）：让 LLM 就用户问题生成一段"可能的答案"文档，用它的向量去检索，
     * 缩小"问题表述"与"答案表述"之间的语义鸿沟。
     * 关闭、问题为空或生成失败时返回 null，调用方据此跳过该路召回。
     */
    private String generateHypotheticalDocument(String question) {
        if (!hydeEnabled || question.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("maxChars", hydeMaxChars);
            String prompt = hydePromptTemplate.render(variables);
            String hypothetical = callHydeWithTimeout(prompt);
            if (hypothetical == null || hypothetical.isBlank()) {
                return null;
            }
            String trimmed = hypothetical.trim();
            if (trimmed.length() > hydeMaxChars) {
                trimmed = trimmed.substring(0, hydeMaxChars);
            }
            log.info("HyDE 生成假设文档: question='{}', length={}", question, trimmed.length());
            return trimmed;
        } catch (Exception e) {
            log.warn("HyDE 生成失败，跳过假设文档召回: {}", e.getMessage());
            return null;
        }
    }

    private String callHydeWithTimeout(String prompt) throws Exception {
        if (hydeTimeoutMs <= 0) {
            return getChatClient().prompt().user(prompt).call().content();
        }
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
            getChatClient().prompt().user(prompt).call().content());
        try {
            return future.get(hydeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private List<RagSourceDTO> buildSources(List<Document> docs) {
        return buildSources(docs, null);
    }

    /**
     * 构造引用来源列表。citedIndexes 非空时，按片段编号（从 1 开始）标记被回答正文实际引用的来源，
     * 用于校验模型是否真的用到了检索内容；为 null（如流式末尾拼来源）时不计算引用标记。
     */
    private List<RagSourceDTO> buildSources(List<Document> docs, Set<Integer> citedIndexes) {
        if (!hasEffectiveHit(docs)) {
            return List.of();
        }

        List<Long> knowledgeBaseIds = docs.stream()
            .map(this::extractKnowledgeBaseId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> nameMap = listService.getKnowledgeBaseNameMap(knowledgeBaseIds);

        List<RagSourceDTO> sources = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Long knowledgeBaseId = extractKnowledgeBaseId(doc);
            Map<String, Object> metadata = doc.getMetadata();
            String fallbackTitle = knowledgeBaseId == null
                ? "未知知识库"
                : nameMap.getOrDefault(knowledgeBaseId, "未知知识库");
            String documentTitle = firstNonBlank(
                metadataValue(metadata, "document_title"),
                fallbackTitle
            );
            String sourceName = metadataValue(metadata, "source_name");
            String category = metadataValue(metadata, "category");
            String sectionTitle = metadataValue(metadata, "section_title");
            boolean cited = citedIndexes != null && citedIndexes.contains(i + 1);
            sources.add(new RagSourceDTO(
                knowledgeBaseId,
                documentTitle,
                sourceName,
                category,
                sectionTitle,
                parseInteger(metadataValue(metadata, "chunk_index")),
                parseInteger(metadataValue(metadata, "chunk_count")),
                buildSourceSnippet(doc.getText()),
                extractSimilarity(doc),
                cited
            ));
        }
        return sources;
    }

    private String buildSourcesMarkdown(List<Document> docs) {
        List<RagSourceDTO> sources = buildSources(docs);
        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n---\n\n## 参考来源\n\n");
        for (int i = 0; i < sources.size(); i++) {
            RagSourceDTO source = sources.get(i);
            sb.append(i + 1)
                .append(". **")
                .append(buildSourceDisplayTitle(source))
                .append("**");
            if (source.similarity() != null) {
                sb.append("（相似度：")
                    .append(String.format(Locale.ROOT, "%.2f", source.similarity()))
                    .append("）");
            }
            sb.append("\n\n")
                .append("   > ")
                .append(source.snippet())
                .append("\n\n");
        }
        return sb.toString();
    }

    private String buildSourceDisplayTitle(RagSourceDTO source) {
        String title = firstNonBlank(source.sourceName(), source.documentTitle(), "未知知识库");
        if (source.sectionTitle() != null && !source.sectionTitle().isBlank()) {
            title += " / " + source.sectionTitle();
        }
        if (source.chunkIndex() != null && source.chunkCount() != null) {
            title += " #" + (source.chunkIndex() + 1) + "/" + source.chunkCount();
        }
        return title;
    }

    private Long extractKnowledgeBaseId(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        Object kbId = metadata.get("kb_id");
        if (kbId == null) {
            kbId = metadata.get("kb_id_long");
        }
        if (kbId == null) {
            return null;
        }
        if (kbId instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(kbId.toString());
        } catch (NumberFormatException e) {
            log.warn("无法解析引用来源知识库ID: kbId={}", kbId);
            return null;
        }
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

    private String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
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

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double extractSimilarity(Document doc) {
        Double score = readFinalScore(doc);
        if (score == null || score.isNaN() || score.isInfinite()) {
            return null;
        }
        return Math.round(score * 10000.0) / 10000.0;
    }

    /**
     * 读取用于展示与置信度计算的最终相关性分。优先取检索后写入 metadata 的 final_score，
     * 它不受 Document.score 后续反复覆盖影响；缺失时回退到 Document.score。
     */
    private Double readFinalScore(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object value = metadata.get("final_score");
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }
        return doc.getScore();
    }

    /**
     * 把检索/重排后的最终相关性分写进每个文档的 metadata，作为展示与置信度计算的稳定口径，
     * 避免 Document.score 在 RRF、重排、相似度搜索之间被反复覆盖后取到中间值。
     */
    private void annotateFinalScore(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        for (Document doc : docs) {
            Double score = doc.getScore();
            if (score != null && !score.isNaN() && !score.isInfinite()) {
                doc.getMetadata().put("final_score", score);
            }
        }
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

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

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

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries,
                                String hypothetical, SearchParams searchParams) {
    }
}
