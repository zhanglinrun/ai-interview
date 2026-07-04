package com.linrun.interview.modules.knowledgebase.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j Driver 配置（对齐业界实践 {@code Neo4jConfiguration}）。
 */
@Configuration
@EnableConfigurationProperties(Neo4jProperties.class)
@ConditionalOnProperty(prefix = "app.ai.rag.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Neo4jConfiguration {

  @Bean(destroyMethod = "close")
  public Driver neo4jDriver(Neo4jProperties properties) {
    return GraphDatabase.driver(
        properties.getUri(),
        AuthTokens.basic(properties.getUsername(), properties.getPassword()));
  }
}
