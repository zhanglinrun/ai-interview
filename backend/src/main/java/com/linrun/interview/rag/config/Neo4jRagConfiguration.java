package com.linrun.interview.rag.config;

import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 领域图检索运行时配置。
 *
 * <p>本地 Compose 默认提供 Neo4j；关闭配置时不创建 Driver，
 * 路由到图数据源失败时由上层回退 ES。</p>
 */
@Configuration
@ConditionalOnProperty(
    prefix = "app.ai.rag.multi-source.neo4j",
    name = "enabled",
    havingValue = "true")
public class Neo4jRagConfiguration {

  @Bean(destroyMethod = "close")
  public Driver neo4jRagDriver(KnowledgeBaseQueryProperties properties) {
    KnowledgeBaseQueryProperties.MultiSource.Neo4j neo4j = properties.getMultiSource().getNeo4j();
    if (neo4j.getUri() == null || neo4j.getUri().isBlank()) {
      throw new IllegalStateException("Neo4j 已启用，但 uri 为空");
    }
    return GraphDatabase.driver(
        neo4j.getUri(),
        AuthTokens.basic(
            neo4j.getUsername() == null ? "" : neo4j.getUsername(),
            neo4j.getPassword() == null ? "" : neo4j.getPassword()));
  }

}
