package interview.guide.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * 知识库查询路由器（移植自 know-engine 的 KnowEngineQueryRouter，面试领域裁剪版）。
 *
 * <p>know-engine 用 LLM 把 query 路由到 ES向量/ES全文/SQL/Neo4j 四种数据源；本项目是面试领域、
 * 单一 ES 知识库数据源，故路由退化为单路：直接返回 ES 向量检索器。
 *
 * <p>保留 {@link QueryRouter} 接口与 know-engine 同构，便于后续接入多数据源（如阶段10 的 SQL/图）
 * 时扩展为 LLM 路由。当前实现不调 LLM，零延迟。
 */
@Slf4j
public class InterviewQueryRouter implements QueryRouter {

    private final ContentRetriever elasticsearchRetriever;

    public InterviewQueryRouter(ContentRetriever elasticsearchRetriever) {
        this.elasticsearchRetriever = elasticsearchRetriever;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        log.debug("[InterviewQueryRouter] 路由到 ES 向量检索器: query='{}'", query.text());
        return List.of(elasticsearchRetriever);
    }
}
