package com.linrun.interview.modules.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 向量库配置属性（对齐 know-engine ElasticSearchProperties）。
 *
 * <p>对应 {@code application.yml} 中 {@code elasticsearch.*} 配置项：
 * <pre>
 * elasticsearch:
 *   host: http://localhost:9200
 *   index-name: interview-guide-vector
 *   dimensions: 1024
 * </pre>
 * embedding 的 baseUrl/apiKey/modelName 复用 {@code app.ai.providers} 多 Provider 配置，
 * 通过 {@link com.linrun.interview.common.ai.LlmProviderRegistry#getDefaultEmbeddingModel()} 获取，
 * 不在此重复配置（与 know-engine 单 Provider 直 new OpenAiEmbeddingModel 的做法不同，
 * 保留本项目的多 Provider 路由能力）。
 */
@Data
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticSearchProperties {

    /** ES 主机地址，如 http://localhost:9200 */
    private String host;

    /** 向量索引名 */
    private String indexName = "interview-guide-vector";

    /** 向量维度（须与 embedding model 输出维度一致，DashScope text-embedding-v3 为 1024） */
    private int dimensions = 1024;
}
