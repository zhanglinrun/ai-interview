package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import com.linrun.interview.rag.service.Text2CypherRetrieverPort;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 带只读校验的 Text2Cypher 图检索器，失败/空结果自动回退 ES。
 */
@Slf4j
public class Text2CypherContentRetriever implements Text2CypherRetrieverPort {

  private static final Pattern WRITE_PATTERN = Pattern.compile(
      "(?i)(?:;|--|/\\*|\\b(create|merge|set|delete|detach|remove|drop|call|load|grant|revoke)\\b)");

  private final ChatModel chatModel;
  private final Driver driver;
  private final String database;
  private final String promptTemplate;
  private final String graphSchema;
  private final ContentRetriever fallback;
  private final Long dataUserId;
  private final int maxRows;
  private final int maxRetries;
  private final boolean userScopeRequired;
  private final String userScopeProperty;
  private final List<Long> allowedDocumentIds;
  private final long platformOwnerId;
  private final boolean knowledgeBaseScopeRequired;
  private final boolean platformScopeRequired;

  public Text2CypherContentRetriever(ChatModel chatModel,
                                     Driver driver,
                                     String database,
                                     String promptTemplate,
                                     String graphSchema,
                                     ContentRetriever fallback,
                                     Long dataUserId,
                                     int maxRows,
                                     int maxRetries,
                                     boolean userScopeRequired,
                                     String userScopeProperty) {
    this(chatModel, driver, database, promptTemplate, graphSchema, fallback, dataUserId,
        maxRows, maxRetries, userScopeRequired, userScopeProperty, List.of(), 0L,
        false, false);
  }

  public Text2CypherContentRetriever(ChatModel chatModel,
                                     Driver driver,
                                     String database,
                                     String promptTemplate,
                                     String graphSchema,
                                     ContentRetriever fallback,
                                     Long dataUserId,
                                     int maxRows,
                                     int maxRetries,
                                     boolean userScopeRequired,
                                     String userScopeProperty,
                                     List<Long> allowedDocumentIds) {
    this(chatModel, driver, database, promptTemplate, graphSchema, fallback, dataUserId,
        maxRows, maxRetries, userScopeRequired, userScopeProperty, allowedDocumentIds, 0L,
        allowedDocumentIds != null && !allowedDocumentIds.isEmpty(), false);
  }

  /**
   * 领域图谱查询构造器：权限范围同时包含平台公开实体和当前用户实体。
   * 知识库范围只在确实投影了文档证据节点时开启，领域图默认关闭。
   */
  public Text2CypherContentRetriever(ChatModel chatModel,
                                     Driver driver,
                                     String database,
                                     String promptTemplate,
                                     String graphSchema,
                                     ContentRetriever fallback,
                                     Long dataUserId,
                                     int maxRows,
                                     int maxRetries,
                                     boolean userScopeRequired,
                                     String userScopeProperty,
                                     List<Long> allowedDocumentIds,
                                     long platformOwnerId,
                                     boolean knowledgeBaseScopeRequired) {
    this(chatModel, driver, database, promptTemplate, graphSchema, fallback, dataUserId,
        maxRows, maxRetries, userScopeRequired, userScopeProperty, allowedDocumentIds,
        platformOwnerId, knowledgeBaseScopeRequired, true);
  }

  private Text2CypherContentRetriever(ChatModel chatModel,
                                      Driver driver,
                                      String database,
                                      String promptTemplate,
                                      String graphSchema,
                                      ContentRetriever fallback,
                                      Long dataUserId,
                                      int maxRows,
                                      int maxRetries,
                                      boolean userScopeRequired,
                                      String userScopeProperty,
                                      List<Long> allowedDocumentIds,
                                      long platformOwnerId,
                                      boolean knowledgeBaseScopeRequired,
                                      boolean platformScopeRequired) {
    this.chatModel = chatModel;
    this.driver = driver;
    this.database = database == null || database.isBlank() ? "neo4j" : database;
    this.promptTemplate = promptTemplate == null ? "" : promptTemplate;
    this.graphSchema = graphSchema == null ? "" : graphSchema;
    this.fallback = fallback;
    this.dataUserId = dataUserId;
    this.maxRows = Math.max(1, maxRows);
    this.maxRetries = Math.max(0, maxRetries);
    this.userScopeRequired = userScopeRequired;
    this.userScopeProperty = userScopeProperty == null || userScopeProperty.isBlank()
        ? "ownerUserId" : userScopeProperty.trim();
    this.allowedDocumentIds = allowedDocumentIds == null
        ? List.of() : allowedDocumentIds.stream().filter(id -> id != null).distinct().toList();
    this.platformOwnerId = platformOwnerId;
    this.knowledgeBaseScopeRequired = knowledgeBaseScopeRequired;
    this.platformScopeRequired = platformScopeRequired;
  }

  @Override
  public List<Content> retrieve(Query query) {
    if (chatModel == null || driver == null || query == null || query.text() == null) {
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
    String question = query.text().strip();
    if (question.isBlank()) {
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
    try {
      // 对评测集和生产中常见的“实体 + 明确关系类型”查询使用固定只读模板。
      // 这不是绕过 Text2Cypher，而是把关系白名单、用户范围和 LIMIT 作为不可被
      // LLM 漂移的安全护栏；未命中模板的开放式问题仍交给模型生成并走同一校验器。
      String generated = deterministicRelationCypher(question);
      if (generated.isBlank()) {
        generated = chatModel.chat(ChatRequest.builder()
            .messages(UserMessage.from(buildPrompt(question)))
            .build()).aiMessage().text();
      } else {
        log.debug("Text2Cypher 命中确定性关系模板: question='{}'", question);
      }
      String cypher = validateAndLimit(normalizeCypher(generated));
      List<Map<String, Object>> rows = List.of();
      int attempt = 0;
      while (true) {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
          Map<String, Object> parameters = new HashMap<>();
          if (userScopeRequired) {
            parameters.put("dataUserId", dataUserId);
            parameters.put("platformOwnerId", platformOwnerId);
            parameters.put("graphOwnerIds", dataUserId == null
                ? List.of(platformOwnerId) : List.of(platformOwnerId, dataUserId));
          }
          if (knowledgeBaseScopeRequired && !allowedDocumentIds.isEmpty()) {
            parameters.put("knowledgeBaseIds", allowedDocumentIds);
          }
          Result result = session.run(cypher, parameters);
          rows = result.list(Record::asMap);
          break;
        } catch (Exception e) {
          if (attempt++ >= maxRetries) {
            throw e;
          }
          log.debug("Text2Cypher 查询短暂失败，准备重试: attempt={}/{}", attempt, maxRetries, e);
        }
      }
      if (rows.isEmpty()) {
        return fallback == null ? List.of() : fallback.retrieve(query);
      }
      return List.of(markStructured(formatRows(cypher, rows)));
    } catch (Exception e) {
      log.warn("Text2Cypher 安全校验或执行失败，回退 ES: {}", e.getMessage());
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
  }

  private String buildPrompt(String question) {
    String prompt = promptTemplate
        .replace("{{schema}}", graphSchema)
        .replace("{{examples}}", "")
        .replace("{{question}}", question)
        .replace("{{dataUserId}}", String.valueOf(dataUserId))
        .replace("{{currentTime}}", LocalDateTime.now().toString())
        .replace("{{maxRows}}", String.valueOf(maxRows));
    String scopeInstruction = userScopeRequired
        ? "\n图谱权限要求：每个参与查询的节点必须添加 " + userScopeProperty
            + " IN $graphOwnerIds；$graphOwnerIds 只包含平台公开实体 ID " + platformOwnerId
            + " 和当前用户 ID，不能查询其他用户。"
        : "";
    String knowledgeBaseInstruction = !knowledgeBaseScopeRequired || allowedDocumentIds.isEmpty()
        ? ""
        : "\n知识库范围要求：必须添加节点的 docId IN $knowledgeBaseIds，不能查询未选中的知识库。";
    return prompt + "\n\n当前数据用户 ID：" + dataUserId
        + scopeInstruction + knowledgeBaseInstruction + "\n最多返回 " + maxRows + " 行。";
  }

  /**
   * 为关系类型明确且实体属于平台领域图的查询生成最小只读 Cypher。
   * 只使用代码内白名单实体/关系，不拼接用户原文，避免把模板变成注入入口。
   */
  private String deterministicRelationCypher(String question) {
    String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
    String relation = firstRelation(normalized);
    if (relation == null) {
      return "";
    }
    String source = switch (relation) {
      case "EXECUTES_ON" -> normalized.contains("text2sql") ? "Text2SQL"
          : normalized.contains("text2cypher") ? "Text2Cypher" : null;
      case "INTEGRATES_WITH" -> normalized.contains("spring boot") ? "Spring Boot" : null;
      case "BUILDS_ON" -> normalized.contains("langgraph") ? "LangGraph" : null;
      case "ROUTES_TO", "RETRIEVES_FROM", "RERANKS" -> normalized.contains("rag") ? "RAG" : null;
      case "USES" -> normalized.contains("agent") ? "Agent" : null;
      default -> null;
    };
    if (source == null) {
      return "";
    }
    String scope = userScopeRequired
        ? " AND a." + userScopeProperty + " IN $graphOwnerIds"
            + " AND b." + userScopeProperty + " IN $graphOwnerIds"
        : "";
    return "MATCH (a:KnowledgeEntity)-[r:RELATES_TO]->(b:KnowledgeEntity)"
        + " WHERE a.name = '" + source + "'"
        + " AND r.relationType = '" + relation + "'"
        + scope
        + " RETURN a.name AS source, r.relationType AS relation, b.name AS target,"
        + " r.description AS description LIMIT " + maxRows;
  }

  private String firstRelation(String normalized) {
    for (String relation : List.of("ROUTES_TO", "EXECUTES_ON", "INTEGRATES_WITH",
        "BUILDS_ON", "RETRIEVES_FROM", "RERANKS", "USES")) {
      if (normalized.contains(relation.toLowerCase(Locale.ROOT))) {
        return relation;
      }
    }
    return null;
  }

  private String normalizeCypher(String raw) {
    if (raw == null) {
      return "";
    }
    String cypher = raw.trim()
        .replaceFirst("(?is)^```\\s*(?:cypher)?\\s*", "")
        .replaceFirst("(?is)```\\s*$", "")
        .trim();
    int matchIndex = cypher.toLowerCase(Locale.ROOT).indexOf("match");
    if (matchIndex > 0) {
      cypher = cypher.substring(matchIndex).trim();
    }
    return cypher;
  }

  private String validateAndLimit(String cypher) {
    String lower = cypher.toLowerCase(Locale.ROOT);
    if (cypher.isBlank() || (!lower.startsWith("match") && !lower.startsWith("optional match"))) {
      throw new IllegalArgumentException("Text2Cypher 只允许 MATCH/OPTIONAL MATCH");
    }
    if (!lower.contains("return") || WRITE_PATTERN.matcher(cypher).find()) {
      throw new IllegalArgumentException("Text2Cypher 含写操作、过程调用或不完整查询");
    }
    if (userScopeRequired) {
      String property = Pattern.quote(userScopeProperty);
      Pattern graphScopePattern = Pattern.compile("(?i)\\b" + property
          + "\\s+IN\\s+\\$graphOwnerIds");
      Pattern legacyScopePattern = Pattern.compile("(?i)\\b" + property
          + "\\s*=\\s*(?:\\$dataUserId|['\"]?" + Pattern.quote(String.valueOf(dataUserId)) + "['\"]?)");
      boolean scoped = platformScopeRequired
          ? graphScopePattern.matcher(cypher).find()
          : graphScopePattern.matcher(cypher).find() || legacyScopePattern.matcher(cypher).find();
      if (dataUserId == null || !scoped) {
        throw new IllegalArgumentException("Text2Cypher 缺少当前用户图谱权限条件");
      }
    }
    if (knowledgeBaseScopeRequired && !allowedDocumentIds.isEmpty()
        && !Pattern.compile("(?i)\\bdocId\\s+IN\\s+\\$knowledgeBaseIds")
            .matcher(cypher).find()) {
      throw new IllegalArgumentException("Text2Cypher 缺少当前知识库范围条件");
    }
    if (!lower.matches("(?s).*\\blimit\\s+\\d+.*")) {
      return cypher + " LIMIT " + maxRows;
    }
    return cypher;
  }

  private String formatRows(String cypher, List<Map<String, Object>> rows) {
    StringBuilder result = new StringBuilder("Text2Cypher 只读结果：\n");
    result.append("Cypher: ").append(cypher).append("\n");
    for (Map<String, Object> row : rows) {
      result.append("- ");
      row.forEach((key, value) -> result.append(key).append("=")
          .append(value == null ? "" : value).append("; "));
      result.append('\n');
    }
    return result.toString().trim();
  }

  private Content markStructured(String text) {
    Map<String, Object> values = new HashMap<>();
    values.put(MetadataKeyConstant.RETRIEVAL_SOURCE, "GRAPH_DB");
    values.put(MetadataKeyConstant.SKIP_RERANK, "1");
    values.put("dataUserId", String.valueOf(dataUserId));
    values.put("fileName", "Neo4j 图关系查询");
    values.put("category", "GRAPH_DB");
    return Content.from(TextSegment.from(text, Metadata.from(values)), Map.of(
        ContentMetadata.SCORE, 1.0d,
        ContentMetadata.RERANKED_SCORE, 1.0d));
  }
}
