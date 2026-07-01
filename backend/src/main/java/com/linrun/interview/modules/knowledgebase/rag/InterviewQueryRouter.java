package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 面试领域查询路由器：ES 知识库（支持双通道）/ Text2SQL / Neo4j / hybrid。
 */
@Slf4j
public class InterviewQueryRouter implements QueryRouter {

    private static final PromptTemplate ROUTE_PROMPT = PromptTemplate.from("""
        你负责把 AI 面试平台中的用户问题路由到数据源。

        数据源：
        - knowledge_base：技术知识库、文档解释、面试题知识点、概念/方案/代码相关问题
        - relational_db：用户自己的简历记录、简历评分、面试历史、答题分数、面试日程等结构化统计查询
        - graph_db：知识点关系、技能依赖、概念关联等图结构查询
        - hybrid：既需要结构化统计，又需要知识库解释的综合问题

        只输出 JSON，不要 markdown：
        {"strategy":"knowledge_base|relational_db|graph_db|hybrid","reasoning":"简短原因","confidence":0.0}

        用户问题：{{query}}
        """);

    private final List<ContentRetriever> elasticsearchRetrievers;
    private final ContentRetriever sqlRetriever;
    private final ContentRetriever neo4jRetriever;
    private final ChatModel chatModel;
    private final boolean enabled;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;
    private final InterviewIntent intentHint;
    private final AtomicBoolean routeProgressSent = new AtomicBoolean(false);

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever) {
        this(List.of(elasticsearchRetriever), null, null, null, false, null, null, null);
    }

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever,
                                ContentRetriever sqlRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback) {
        this(List.of(elasticsearchRetriever), sqlRetriever, null, chatModel, enabled, progressCallback, null, null);
    }

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever,
                                ContentRetriever sqlRetriever,
                                ContentRetriever neo4jRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback,
                                RagQueryTrace trace) {
        this(List.of(elasticsearchRetriever), sqlRetriever, neo4jRetriever, chatModel, enabled,
            progressCallback, trace, null);
    }

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever,
                                ContentRetriever sqlRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback,
                                RagQueryTrace trace) {
        this(List.of(elasticsearchRetriever), sqlRetriever, null, chatModel, enabled, progressCallback, trace, null);
    }

    public InterviewQueryRouter(List<ContentRetriever> elasticsearchRetrievers,
                                ContentRetriever sqlRetriever,
                                ContentRetriever neo4jRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback,
                                RagQueryTrace trace) {
        this(elasticsearchRetrievers, sqlRetriever, neo4jRetriever, chatModel, enabled, progressCallback, trace, null);
    }

    public InterviewQueryRouter(List<ContentRetriever> elasticsearchRetrievers,
                                ContentRetriever sqlRetriever,
                                ContentRetriever neo4jRetriever,
                                ChatModel chatModel,
                                boolean enabled,
                                Consumer<String> progressCallback,
                                RagQueryTrace trace,
                                InterviewIntent intentHint) {
        this.elasticsearchRetrievers = elasticsearchRetrievers == null || elasticsearchRetrievers.isEmpty()
            ? List.of()
            : List.copyOf(elasticsearchRetrievers);
        this.sqlRetriever = sqlRetriever;
        this.neo4jRetriever = neo4jRetriever;
        this.chatModel = chatModel;
        this.enabled = enabled;
        this.progressCallback = progressCallback;
        this.trace = trace;
        this.intentHint = intentHint;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        if (elasticsearchRetrievers.isEmpty()) {
            return List.of();
        }
        if (!enabled || chatModel == null) {
            return elasticsearchRetrievers;
        }
        if (sqlRetriever == null && neo4jRetriever == null) {
            return elasticsearchRetrievers;
        }
        if (progressCallback != null && routeProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("正在选择检索数据源...");
        }
        String strategy = routeStrategy(query.text());
        log.debug("[InterviewQueryRouter] query='{}', strategy={}", query.text(), strategy);
        return switch (strategy) {
            case "relational_db" -> List.of(sqlRetriever);
            case "graph_db" -> neo4jRetriever != null ? List.of(neo4jRetriever) : elasticsearchRetrievers;
            case "hybrid" -> hybridRetrievers();
            default -> elasticsearchRetrievers;
        };
    }

    private List<ContentRetriever> hybridRetrievers() {
        List<ContentRetriever> retrievers = new ArrayList<>();
        if (sqlRetriever != null) {
            retrievers.add(sqlRetriever);
        }
        if (neo4jRetriever != null) {
            retrievers.add(neo4jRetriever);
        }
        retrievers.addAll(elasticsearchRetrievers);
        return retrievers;
    }

    private String routeStrategy(String question) {
        String rule = ruleBasedStrategy(question);
        if (rule != null) {
            if (trace != null) {
                trace.route(rule, "规则命中结构化查询关键词");
            }
            return rule;
        }
        try {
            String response = chatModel.chat(ROUTE_PROMPT.apply(Map.of("query", question)).text());
            var node = JsonUtil.fixAndParse(response);
            String strategy = node.path("strategy").asText("knowledge_base");
            String normalized = switch (strategy) {
                case "relational_db", "graph_db", "hybrid" -> strategy;
                default -> "knowledge_base";
            };
            if (trace != null) {
                trace.route(normalized, node.path("reasoning").asText(""));
            }
            return normalized;
        } catch (Exception e) {
            log.warn("[InterviewQueryRouter] LLM 路由失败，降级 hybrid 多路检索: {}", e.getMessage(), e);
            if (trace != null) {
                trace.route("hybrid", "路由失败，降级 hybrid 多路检索");
            }
            return sqlRetriever != null || neo4jRetriever != null ? "hybrid" : "knowledge_base";
        }
    }

    private String ruleBasedStrategy(String question) {
        if (intentHint != null) {
            String intentRoute = routeByIntent(intentHint);
            if (intentRoute != null) {
                return intentRoute;
            }
        }
        String q = question == null ? "" : question.toLowerCase();
        if (q.contains("平均分") || q.contains("最高分") || q.contains("最低分")
            || q.contains("统计") || q.contains("多少次") || q.contains("几次")
            || q.contains("面试安排") || q.contains("日程") || q.contains("哪家公司")
            || q.contains("简历评分") || q.contains("历史面试")
            || q.contains("简历记录") || q.contains("投递记录") || q.contains("offer")
            || q.contains("模拟面试次数") || q.contains("答题分数")) {
            return "relational_db";
        }
        if (q.contains("知识点关系") || q.contains("技能依赖") || q.contains("概念关联")
            || q.contains("知识图谱") || q.contains("依赖链") || q.contains("关联路径")
            || q.contains("前置知识") || q.contains("学习路径")) {
            return "graph_db";
        }
        if ((q.contains("结合") || q.contains("同时") || q.contains("并且"))
            && (q.contains("历史") || q.contains("分数") || q.contains("简历"))
            && (q.contains("解释") || q.contains("分析") || q.contains("为什么"))) {
            return "hybrid";
        }
        return null;
    }

    private String routeByIntent(InterviewIntent intent) {
        return switch (intent) {
            case DATA_QUERY, RESUME_STATS, SCHEDULE -> "relational_db";
            case CODE_REVIEW, INTERVIEW_PREP, TECH_KB, CAREER, OFF_TOPIC -> null;
        };
    }
}
