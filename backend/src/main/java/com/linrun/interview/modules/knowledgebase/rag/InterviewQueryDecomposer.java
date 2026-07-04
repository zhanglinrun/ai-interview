package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Query Decomposition 查询分解器（P2 Agentic RAG）。
 *
 * <p>包装现有改写/HyDE 组合器（{@link InterviewCompositeQueryTransformer}）：先走原改写链，
 * 再对复杂问题（多跳/对比/综合）用 LLM 拆解 2-4 个可独立检索的子查询，与原 query 一起返回。
 * {@code DefaultRetrievalAugmentor} 对多 query 并行检索（虚拟线程 executor），
 * 结果进 {@link InterviewReRankingContentAggregator} 跨 query RRF 融合去重。
 *
 * <p>成本控制：
 * <ul>
 *   <li>规则预筛（{@link #isLikelyComplex}）——不含对比/多跳标记的简单问题直接跳过，零额外 LLM 调用</li>
 *   <li>LLM 二次判定 {@code complex=false} 时同样跳过</li>
 *   <li>分解失败/解析失败降级返回原改写链结果，不阻断检索</li>
 * </ul>
 */
@Slf4j
public class InterviewQueryDecomposer implements QueryTransformer {

    /** 复杂问题标记：对比 / 多跳 / 综合类关键词（规则预筛，命中才触发 LLM 分解判定）。 */
    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
        "对比|区别|差异|异同|优缺点|相比|哪个更|哪种更|先后|分别|结合|联系|"
            + "(?:和|与|跟).{0,20}(?:关系|哪个|如何协同)|为什么.{0,30}(?:而|但)|vs|VS|versus");

    private static final int MIN_QUESTION_LENGTH = 8;
    private static final String PROGRESS_DECOMPOSING = "正在分解复杂问题...";

    private final QueryTransformer delegate;
    private final ChatModel chatModel;
    private final PromptTemplate promptTemplate;
    private final int maxSubQueries;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;

    public InterviewQueryDecomposer(QueryTransformer delegate,
                                    ChatModel chatModel,
                                    PromptTemplate promptTemplate,
                                    int maxSubQueries,
                                    Consumer<String> progressCallback,
                                    RagQueryTrace trace) {
        this.delegate = delegate;
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.maxSubQueries = maxSubQueries;
        this.progressCallback = progressCallback;
        this.trace = trace;
    }

    @Override
    public List<Query> transform(Query query) {
        List<Query> base = new ArrayList<>(delegate.transform(query));
        if (chatModel == null || promptTemplate == null || !isLikelyComplex(query.text())) {
            return base;
        }
        Query primary = base.isEmpty() ? query : base.get(0);
        try {
            if (progressCallback != null) {
                progressCallback.accept(PROGRESS_DECOMPOSING);
            }
            String response = chatModel.chat(promptTemplate.render(Map.of(
                "question", primary.text(),
                "maxSubQueries", maxSubQueries)));
            JsonNode node = JsonUtil.fixAndParse(response);
            if (!node.path("complex").asBoolean(false)) {
                log.debug("[InterviewQueryDecomposer] LLM 判定非复杂问题，跳过分解: '{}'", query.text());
                return base;
            }
            List<String> subQueries = parseSubQueries(node, primary.text());
            if (subQueries.size() < 2) {
                return base;
            }
            if (trace != null) {
                trace.decomposedQueries(subQueries);
            }
            for (String sub : subQueries) {
                base.add(primary.metadata() == null
                    ? Query.from(sub)
                    : Query.from(sub, primary.metadata()));
            }
            log.info("[InterviewQueryDecomposer] 复杂问题分解: origin='{}', subQueries={}",
                query.text(), subQueries);
            return base;
        } catch (Exception e) {
            log.warn("[InterviewQueryDecomposer] 分解失败，退回原查询链: {}", e.getMessage(), e);
            return base;
        }
    }

    private List<String> parseSubQueries(JsonNode node, String primaryText) {
        List<String> subQueries = new ArrayList<>();
        for (JsonNode sub : node.path("subQueries")) {
            String text = sub.asText("").trim();
            if (!text.isBlank() && !text.equals(primaryText) && !subQueries.contains(text)) {
                subQueries.add(text);
            }
            if (subQueries.size() >= maxSubQueries) {
                break;
            }
        }
        return subQueries;
    }

    /** 规则预筛：短问题与不含对比/多跳标记的问题直接跳过 LLM 分解判定。 */
    static boolean isLikelyComplex(String question) {
        if (question == null || question.trim().length() < MIN_QUESTION_LENGTH) {
            return false;
        }
        return COMPLEX_PATTERN.matcher(question).find();
    }
}
