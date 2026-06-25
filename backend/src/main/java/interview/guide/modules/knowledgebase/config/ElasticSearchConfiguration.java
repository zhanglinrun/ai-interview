package interview.guide.modules.knowledgebase.config;

import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 向量库配置（对齐 know-engine ElasticSearchConfiguration）。
 *
 * <p>提供 {@link RestClient} 和 {@link ElasticsearchEmbeddingStore} 两个 bean，
 * 替代 Spring AI 的 PgVectorStore。embedding model 不在此重复创建——本项目通过
 * {@link interview.guide.common.ai.LlmProviderRegistry#getDefaultEmbeddingModel()}
 * 统一获取 LC4j {@code EmbeddingModel}，支持多 Provider 路由，比 know-engine
 * 单 Provider 直 new 的方式更灵活。
 */
@Configuration
@EnableConfigurationProperties(ElasticSearchProperties.class)
@Slf4j
public class ElasticSearchConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient(ElasticSearchProperties properties) {
        log.info("[ElasticSearch] RestClient 初始化: host={}", properties.getHost());
        return RestClient
            .builder(HttpHost.create(properties.getHost()))
            .build();
    }

    @ConditionalOnMissingBean
    @Bean
    public ElasticsearchEmbeddingStore elasticsearchEmbeddingStore(
        RestClient restClient, ElasticSearchProperties properties) {
        log.info("[ElasticSearch] ElasticsearchEmbeddingStore 初始化: indexName={}, dimensions={}",
            properties.getIndexName(), properties.getDimensions());
        return ElasticsearchEmbeddingStore.builder()
            .restClient(restClient)
            .indexName(properties.getIndexName())
            .build();
    }
}
