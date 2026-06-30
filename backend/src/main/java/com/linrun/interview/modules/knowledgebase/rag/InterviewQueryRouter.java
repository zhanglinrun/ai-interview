package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 面试领域查询路由器：ES 知识库 / Text2SQL / 双路。
 */
@Slf4j
public class InterviewQueryRouter implements QueryRouter {

    private static final PromptTemplate ROUTE_PROMPT = PromptTemplate.from("""
        你负责把 AI 面试平台中的用户问题路由到数据源。

        数据源：
        - knowledge_base：技术知识库、文档解释、面试题知识点、概念/方案/代码相关问题
        - relational_db：用户自己的简历记录、简历评分、面试历史、答题分数、面试日程等结构化统计查询
        - hybrid：既需要结构化统计，又需要知识库解释的综合问题

        只输出 JSON，不要 markdown：
        {"strategy":"knowledge_base|relational_db|hybrid","reasoning":"简短原因","confidence":0.0}

        用户问题：{{query}}
        """);

    private final ContentRetriever elasticsearchRetriever;
    private final ContentRetriever sqlRetriever;
    private final ChatModel chatModel;
    private final boolean enabled;
    private final Consumer<String> progressCallback;
    private final AtomicBoolean routeProgressSent = new AtomicBoolean(false);

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever) {
        this(elasticsearchRetriever, null, null, false, null);
    }

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever,
                                ContentRetriever sqlRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback) {
        this.elasticsearchRetriever = elasticsearchRetriever;
        this.sqlRetriever = sqlRetriever;
        this.chatModel = chatModel;
        this.enabled = enabled;
        this.progressCallback = progressCallback;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        if (!enabled || sqlRetriever == null || chatModel == null) {
            return List.of(elasticsearchRetriever);
        }
        if (progressCallback != null && routeProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在选择检索数据源...");
        }
        String strategy = routeStrategy(query.text());
        log.debug("[InterviewQueryRouter] query='{}', strategy={}", query.text(), strategy);
        return switch (strategy) {
            case "relational_db" -> List.of(sqlRetriever);
            case "hybrid" -> List.of(sqlRetriever, elasticsearchRetriever);
            default -> List.of(elasticsearchRetriever);
        };
    }

    private String routeStrategy(String question) {
        String rule = ruleBasedStrategy(question);
        if (rule != null) {
            return rule;
        }
        try {
            String response = chatModel.chat(ROUTE_PROMPT.apply(Map.of("query", question)).text());
            var node = JsonUtil.fixAndParse(response);
            String strategy = node.path("strategy").asText("knowledge_base");
            return switch (strategy) {
                case "relational_db", "hybrid" -> strategy;
                default -> "knowledge_base";
            };
        } catch (Exception e) {
            log.warn("[InterviewQueryRouter] LLM 路由失败，降级 ES: {}", e.getMessage(), e);
            return "knowledge_base";
        }
    }

    private String ruleBasedStrategy(String question) {
        String q = question == null ? "" : question.toLowerCase();
        if (q.contains("平均分") || q.contains("最高分") || q.contains("最低分")
            || q.contains("统计") || q.contains("多少次") || q.contains("几次")
            || q.contains("面试安排") || q.contains("日程") || q.contains("哪家公司")
            || q.contains("简历评分") || q.contains("历史面试")) {
            return "relational_db";
        }
        return null;
    }
}
