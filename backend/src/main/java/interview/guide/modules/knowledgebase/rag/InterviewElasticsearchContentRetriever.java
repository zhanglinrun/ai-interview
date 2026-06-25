package interview.guide.modules.knowledgebase.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import interview.guide.modules.knowledgebase.constant.MetadataKeyConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识库 Elasticsearch 内容检索器（移植自 know-engine 的 KnowEngineElasticsearchContentRetriever）。
 *
 * <p>实现 LC4j {@link ContentRetriever}，供 {@code DefaultRetrievalAugmentor} 编排。组合
 * {@link ElasticsearchEmbeddingStore} + {@link EmbeddingModel}（阶段5 已有的 bean），做 KNN 向量检索，
 * 把 {@code EmbeddingMatch} 转成带 {@link ContentMetadata#SCORE} 与
 * {@link ContentMetadata#EMBEDDING_ID} 的 {@link Content}，供 Aggregator 融合/rerank。
 *
 * <p>裁剪：know-engine 原版还支持全文/混合检索 + parent/brother 扩展（依赖 KnowledgeSegmentService 从
 * Redis 读父分段）。本项目阶段5 已简化为单一 KNN 向量检索，parent/brother 元数据已在 ES 中
 * （MarkdownHeaderBrotherTextSplitter 写入），扩展能力留待后续阶段补；权限过滤由 kb_id filter 限定。
 *
 * <p>每次对话按 knowledgeBaseIds 构建 filter，故为 prototype 作用域，由调用方 new 或工厂创建。
 */
@Slf4j
public class InterviewElasticsearchContentRetriever implements ContentRetriever {

    private final ElasticsearchEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;

    public InterviewElasticsearchContentRetriever(ElasticsearchEmbeddingStore embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   int maxResults,
                                                   double minScore,
                                                   List<Long> knowledgeBaseIds) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = buildKbFilter(knowledgeBaseIds);
    }

    @Override
    public List<Content> retrieve(Query query) {
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
        var builder = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(Math.max(maxResults, 1));
        if (minScore > 0) {
            builder.minScore(minScore);
        }
        if (filter != null) {
            builder.filter(filter);
        }

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(builder.build());
        List<Content> contents = result.matches().stream()
            .map(this::toContent)
            .collect(Collectors.toList());
        log.info("[InterviewElasticsearchContentRetriever] 检索完成: query='{}', 命中 {} 条",
            query.text(), contents.size());
        return contents;
    }

    private Content toContent(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();
        // 检索器写入 SCORE/EMBEDDING_ID，供 Aggregator 融合/rerank 与 DefaultContent 去重使用
        Metadata enriched = metadata.put(ContentMetadata.SCORE.name(), match.score())
            .put(MetadataKeyConstant.EMBEDDING_ID, match.embeddingId());
        TextSegment scored = new TextSegment(segment.text(), enriched);
        return Content.from(scored, Map.of(
            ContentMetadata.SCORE, match.score(),
            ContentMetadata.EMBEDDING_ID, match.embeddingId()));
    }

    /**
     * 构建 kb_id metadata filter（任一知识库命中）。与 KnowledgeBaseVectorService.buildKbFilter 一致。
     */
    private Filter buildKbFilter(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return null;
        }
        List<Filter> filters = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(id -> metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(String.valueOf(id)))
            .toList();
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return filters.stream().reduce((a, b) -> a.or(b))
            .orElse(null);
    }
}
