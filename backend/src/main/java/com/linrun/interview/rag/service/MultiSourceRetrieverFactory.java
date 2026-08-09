package com.linrun.interview.rag.service;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.neo4j.driver.Driver;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;

/**
 * 为每次查询创建带用户/回退上下文的结构化检索器。
 * Neo4j 检索器面向领域实体关系；文档分段上下文仍由 ES + MySQL/Redis 扩展。
 */
@Slf4j
@Service
public class MultiSourceRetrieverFactory {

  private final LlmProviderRegistry llmProviderRegistry;
  private final KnowledgeBaseQueryProperties properties;
  private final RagSqlSchemaService sqlSchemaService;
  private final ResourceLoader resourceLoader;
  private final Optional<Driver> neo4jDriver;
  private final DataSource dataSource;

  public MultiSourceRetrieverFactory(LlmProviderRegistry llmProviderRegistry,
                                     KnowledgeBaseQueryProperties properties,
                                     RagSqlSchemaService sqlSchemaService,
                                     ResourceLoader resourceLoader,
                                     Optional<Driver> neo4jDriver,
                                     DataSource dataSource) {
    this.llmProviderRegistry = llmProviderRegistry;
    this.properties = properties;
    this.sqlSchemaService = sqlSchemaService;
    this.resourceLoader = resourceLoader;
    this.neo4jDriver = neo4jDriver;
    this.dataSource = dataSource;
  }

  public Optional<ContentRetriever> createSql(ContentRetriever fallback,
                                              Long modelUserId,
                                              Long dataUserId) {
    KnowledgeBaseQueryProperties.MultiSource multi = properties.getMultiSource();
    if (!multi.isEnabled() || !multi.getSql().isEnabled()) {
      return Optional.empty();
    }
    try {
      KnowledgeBaseQueryProperties.MultiSource.Sql sql = multi.getSql();
      return Optional.of(new Text2SqlContentRetriever(
          chatModel(modelUserId),
          new JdbcTemplate(dataSource),
          loadPrompt(sql.getPromptPath()),
          sqlSchemaService.schemaDescription(),
          sqlSchemaService.allowedTables(),
          fallback,
          dataUserId,
          sql.getMaxRows()));
    } catch (Exception e) {
      log.warn("Text2SQL 检索器创建失败，跳过结构化路由: {}", e.getMessage());
      return Optional.empty();
    }
  }

  public Optional<ContentRetriever> createNeo4j(ContentRetriever fallback,
                                                 Long modelUserId,
                                                 Long dataUserId) {
    return createNeo4j(fallback, modelUserId, dataUserId, List.of());
  }

  public Optional<ContentRetriever> createNeo4j(ContentRetriever fallback,
                                                 Long modelUserId,
                                                 Long dataUserId,
                                                 List<Long> knowledgeBaseIds) {
    KnowledgeBaseQueryProperties.MultiSource multi = properties.getMultiSource();
    if (!multi.isEnabled() || !multi.getNeo4j().isEnabled() || neo4jDriver.isEmpty()) {
      return Optional.empty();
    }
    try {
      KnowledgeBaseQueryProperties.MultiSource.Neo4j neo4j = multi.getNeo4j();
      return Optional.of(new Text2CypherContentRetriever(
          chatModel(modelUserId),
          neo4jDriver.get(),
          neo4j.getDatabase(),
          loadPrompt(neo4j.getPromptPath()),
          neo4j.getSchema(),
          fallback,
          dataUserId,
          neo4j.getMaxRows(),
          neo4j.getMaxRetries(),
          neo4j.isUserScopeRequired(),
          neo4j.getUserScopeProperty(),
          knowledgeBaseIds,
          neo4j.getPlatformOwnerId(),
          neo4j.isKnowledgeBaseScopeRequired()));
    } catch (Exception e) {
      log.warn("Text2Cypher 检索器创建失败，跳过图路由: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private ChatModel chatModel(Long userId) {
    if (userId != null) {
      return llmProviderRegistry.getUserChatModel(userId);
    }
    return llmProviderRegistry.getChatModelWithModel(null, properties.getMultiSource().getRouteModel());
  }

  private String loadPrompt(String location) {
    try {
      return resourceLoader.getResource(location).getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("无法加载 RAG Prompt: " + location, e);
    }
  }

}
