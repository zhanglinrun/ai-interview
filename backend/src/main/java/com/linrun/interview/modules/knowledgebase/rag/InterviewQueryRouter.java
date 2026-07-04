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

    private static final String SOURCE_KNOWLEDGE_BASE =
        "- knowledge_base：技术知识库、文档解释、面试题知识点、概念/方案/代码相关问题";
    private static final String SOURCE_RELATIONAL_DB =
        "- relational_db：用户自己的简历记录、简历评分、面试历史、答题分数、面试日程等结构化统计查询";
    private static final String SOURCE_GRAPH_DB =
        "- graph_db：知识点关系、技能依赖、概念关联等图结构查询";
    private static final String SOURCE_HYBRID =
        "- hybrid：需要多个数据源协同回答的综合问题";

    private final List<ContentRetriever> elasticsearchRetrievers;
    private final ContentRetriever sqlRetriever;
    private final ContentRetriever neo4jRetriever;
    private final ChatModel chatModel;
    private final boolean enabled;
    private final Consumer<String> progressCallback;
    private final RagQueryTrace trace;
    private final InterviewIntent intentHint;
    private final AtomicBoolean routeProgressSent = new AtomicBoolean(false);

    private InterviewQueryRouter(Builder builder) {
        this.elasticsearchRetrievers = builder.elasticsearchRetrievers == null
            || builder.elasticsearchRetrievers.isEmpty()
            ? List.of()
            : List.copyOf(builder.elasticsearchRetrievers);
        this.sqlRetriever = builder.sqlRetriever;
        this.neo4jRetriever = builder.neo4jRetriever;
        this.chatModel = builder.chatModel;
        this.enabled = builder.enabled;
        this.progressCallback = builder.progressCallback;
        this.trace = builder.trace;
        this.intentHint = builder.intentHint;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder：替代原先 6 个重载构造，按需装配可选依赖。 */
    public static final class Builder {
        private List<ContentRetriever> elasticsearchRetrievers = List.of();
        private ContentRetriever sqlRetriever;
        private ContentRetriever neo4jRetriever;
        private ChatModel chatModel;
        private boolean enabled;
        private Consumer<String> progressCallback;
        private RagQueryTrace trace;
        private InterviewIntent intentHint;

        public Builder elasticsearchRetrievers(List<ContentRetriever> retrievers) {
            this.elasticsearchRetrievers = retrievers;
            return this;
        }

        public Builder elasticsearchRetriever(ContentRetriever retriever) {
            this.elasticsearchRetrievers = retriever == null ? List.of() : List.of(retriever);
            return this;
        }

        public Builder sqlRetriever(ContentRetriever sqlRetriever) {
            this.sqlRetriever = sqlRetriever;
            return this;
        }

        public Builder neo4jRetriever(ContentRetriever neo4jRetriever) {
            this.neo4jRetriever = neo4jRetriever;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder progressCallback(Consumer<String> progressCallback) {
            this.progressCallback = progressCallback;
            return this;
        }

        public Builder trace(RagQueryTrace trace) {
            this.trace = trace;
            return this;
        }

        public Builder intentHint(InterviewIntent intentHint) {
            this.intentHint = intentHint;
            return this;
        }

        public InterviewQueryRouter build() {
            return new InterviewQueryRouter(this);
        }
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
            case "relational_db" -> sqlRetriever != null ? List.of(sqlRetriever) : elasticsearchRetrievers;
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
            String response = chatModel.chat(buildRoutePrompt().apply(Map.of("query", question)).text());
            var node = JsonUtil.fixAndParse(response);
            String strategy = node.path("strategy").asText("knowledge_base");
            String normalized = switch (strategy) {
                case "relational_db" -> sqlRetriever != null ? strategy : "knowledge_base";
                case "graph_db" -> neo4jRetriever != null ? strategy : "knowledge_base";
                case "hybrid" -> strategy;
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

    /** 按当前实际装配的检索器动态生成路由 prompt，未启用的数据源不进入候选。 */
    private PromptTemplate buildRoutePrompt() {
        StringBuilder sources = new StringBuilder(SOURCE_KNOWLEDGE_BASE);
        StringBuilder options = new StringBuilder("knowledge_base");
        if (sqlRetriever != null) {
            sources.append('\n').append(SOURCE_RELATIONAL_DB);
            options.append("|relational_db");
        }
        if (neo4jRetriever != null) {
            sources.append('\n').append(SOURCE_GRAPH_DB);
            options.append("|graph_db");
        }
        sources.append('\n').append(SOURCE_HYBRID);
        options.append("|hybrid");
        return PromptTemplate.from("""
            你负责把 AI 面试平台中的用户问题路由到数据源。

            数据源：
            %s

            只输出 JSON，不要 markdown：
            {"strategy":"%s","reasoning":"简短原因","confidence":0.0}

            用户问题：{{query}}
            """.formatted(sources, options));
    }

    private String ruleBasedStrategy(String question) {
        if (intentHint != null) {
            String intentRoute = routeByIntent(intentHint);
            if (intentRoute != null) {
                return intentRoute;
            }
        }
        String q = question == null ? "" : question.toLowerCase();
        if (sqlRetriever != null
            && (q.contains("平均分") || q.contains("最高分") || q.contains("最低分")
            || q.contains("统计") || q.contains("多少次") || q.contains("几次")
            || q.contains("面试安排") || q.contains("日程") || q.contains("哪家公司")
            || q.contains("简历评分") || q.contains("历史面试")
            || q.contains("简历记录") || q.contains("投递记录") || q.contains("offer")
            || q.contains("模拟面试次数") || q.contains("答题分数"))) {
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
            case DATA_QUERY, RESUME_STATS, SCHEDULE -> sqlRetriever != null ? "relational_db" : null;
            case CODE_REVIEW, INTERVIEW_PREP, TECH_KB, CAREER, OFF_TOPIC -> null;
        };
    }
}
