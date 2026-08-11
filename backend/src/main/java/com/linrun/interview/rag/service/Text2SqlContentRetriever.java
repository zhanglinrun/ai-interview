package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import com.linrun.interview.rag.service.Text2SqlRetrieverPort;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带安全闸的 Text2SQL 结构化检索器。
 *
 * <p>链路为“LLM 生成 → 只读/单语句校验 → 表白名单校验 → 用户隔离校验 → JDBC 执行”。
 * 任一环节失败或无数据都回退 ES，避免结构化数据故障阻断知识库问答。</p>
 */
@Slf4j
public class Text2SqlContentRetriever implements Text2SqlRetrieverPort {

  private static final Pattern TABLE_PATTERN = Pattern.compile(
      "(?i)\\b(?:from|join)\\s+`?([a-zA-Z][a-zA-Z0-9_]*)`?");
  private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\s+\\d+");
  private static final Pattern USER_ID_PATTERN = Pattern.compile("(?i)\\buser_id\\b");
  private static final Pattern USER_SCOPE_PATTERN = Pattern.compile(
      "(?i)\\buser_id\\s*=\\s*['\"]?(\\d+)['\"]?");
  private static final Pattern WRITE_PATTERN = Pattern.compile(
      "(?i)(?:--|/\\*|\\b(insert|update|delete|drop|alter|truncate|create|rename|grant|revoke|call|load|outfile|dumpfile)\\b)");
  private static final Pattern INTERNAL_STATEMENT_PATTERN = Pattern.compile(";\\s*\\S");
  private static final Set<String> USER_SCOPED_TABLES = Set.of(
      "users", "documents", "document_versions", "document_segments",
      "resumes", "resume_analyses", "interview_sessions", "interview_answers",
      "chat_sessions", "chat_messages", "chat_memories", "rag_query_traces", "coding_attempts",
      "judge_submissions", "training_tasks", "capability_profiles");

  private final ChatModel chatModel;
  private final JdbcTemplate jdbcTemplate;
  private final String promptTemplate;
  private final String databaseStructure;
  private final Set<String> allowedTables;
  private final ContentRetriever fallback;
  private final Long dataUserId;
  private final int maxRows;

  public Text2SqlContentRetriever(ChatModel chatModel,
                                  JdbcTemplate jdbcTemplate,
                                  String promptTemplate,
                                  String databaseStructure,
                                  Set<String> allowedTables,
                                  ContentRetriever fallback,
                                  Long dataUserId,
                                  int maxRows) {
    this.chatModel = chatModel;
    this.jdbcTemplate = jdbcTemplate;
    this.promptTemplate = promptTemplate == null ? "" : promptTemplate;
    this.databaseStructure = databaseStructure == null ? "" : databaseStructure;
    this.allowedTables = allowedTables == null ? Set.of() : Set.copyOf(allowedTables);
    this.fallback = fallback;
    this.dataUserId = dataUserId;
    this.maxRows = Math.max(1, maxRows);
  }

  @Override
  public List<Content> retrieve(Query query) {
    if (chatModel == null || jdbcTemplate == null || query == null || query.text() == null) {
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
    String question = query.text().strip();
    if (question.isBlank()) {
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
    try {
      String generated = chatModel.chat(ChatRequest.builder()
          .messages(UserMessage.from(buildPrompt(question)))
          .build()).aiMessage().text();
      String sql = normalizeSql(generated);
      sql = validateSql(sql);
      List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
      if (rows.isEmpty()) {
        return fallback == null ? List.of() : fallback.retrieve(query);
      }
      return List.of(markStructured(formatRows(sql, rows)));
    } catch (Exception e) {
      log.warn("Text2SQL 安全校验或执行失败，回退 ES: {}", e.getMessage());
      return fallback == null ? List.of() : fallback.retrieve(query);
    }
  }

  private String buildPrompt(String question) {
    String prompt = promptTemplate
        .replace("{{databaseStructure}}", databaseStructure)
        .replace("{{question}}", question)
        .replace("{{dataUserId}}", String.valueOf(dataUserId))
        .replace("{{currentTime}}", LocalDateTime.now().toString())
        .replace("{{maxRows}}", String.valueOf(maxRows));
    return prompt + "\n\n当前数据用户 ID：" + dataUserId
        + "\n当前时间：" + LocalDateTime.now()
        + "\n最多返回 " + maxRows + " 行。";
  }

  private String normalizeSql(String raw) {
    if (raw == null) {
      return "";
    }
    String sql = raw.trim()
        .replaceFirst("(?is)^```\\s*(?:sql)?\\s*", "")
        .replaceFirst("(?is)```\\s*$", "")
        .trim();
    int selectIndex = sql.toLowerCase(Locale.ROOT).indexOf("select");
    if (selectIndex > 0) {
      sql = sql.substring(selectIndex).trim();
    }
    // LLMs often terminate a single SELECT with ';'. Keep that harmless
    // delimiter out of the safety check while still rejecting multi-statements.
    sql = sql.replaceFirst(";\\s*$", "").trim();
    return sql;
  }

  private String validateSql(String sql) {
    if (sql.isBlank() || !sql.toLowerCase(Locale.ROOT).startsWith("select")) {
      throw new IllegalArgumentException("Text2SQL 只允许 SELECT");
    }
    if (WRITE_PATTERN.matcher(sql).find() || INTERNAL_STATEMENT_PATTERN.matcher(sql).find()) {
      throw new IllegalArgumentException("Text2SQL 包含写操作或多语句符号");
    }
    Matcher tableMatcher = TABLE_PATTERN.matcher(sql);
    Set<String> referenced = new LinkedHashSet<>();
    while (tableMatcher.find()) {
      referenced.add(tableMatcher.group(1).toLowerCase(Locale.ROOT));
    }
    if (referenced.isEmpty() || !allowedTables.containsAll(referenced)) {
      throw new IllegalArgumentException("Text2SQL 访问了未授权表: " + referenced);
    }
    if (referenced.stream().anyMatch(USER_SCOPED_TABLES::contains)
        && (dataUserId == null || !USER_ID_PATTERN.matcher(sql).find())) {
      throw new IllegalArgumentException("用户数据查询必须携带 user_id 过滤条件");
    }
    if (referenced.stream().anyMatch(USER_SCOPED_TABLES::contains) && dataUserId != null) {
      Matcher scopeMatcher = USER_SCOPE_PATTERN.matcher(sql);
      boolean scopedToCurrentUser = false;
      while (scopeMatcher.find()) {
        if (String.valueOf(dataUserId).equals(scopeMatcher.group(1))) {
          scopedToCurrentUser = true;
          break;
        }
      }
      if (!scopedToCurrentUser) {
        throw new IllegalArgumentException("user_id 未绑定当前数据用户");
      }
    }
    if (!LIMIT_PATTERN.matcher(sql).find()) {
      // 只追加硬上限，不替换模型生成的更小 LIMIT。
      sql = sql + " LIMIT " + maxRows;
    }
    return sql;
  }

  private String formatRows(String sql, List<Map<String, Object>> rows) {
    StringBuilder result = new StringBuilder("Text2SQL 只读结果：\n");
    result.append("SQL: ").append(sql).append("\n");
    result.append("行数: ").append(rows.size()).append("\n");
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
    values.put(MetadataKeyConstant.RETRIEVAL_SOURCE, "RELATIONAL_DB");
    values.put(MetadataKeyConstant.SKIP_RERANK, "1");
    values.put("dataUserId", String.valueOf(dataUserId));
    values.put("fileName", "MySQL 结构化查询");
    values.put("category", "RELATIONAL_DB");
    // 结构化查询已经通过只读、白名单和用户隔离校验，作为一等证据参与 grounded 计算。
    return Content.from(TextSegment.from(text, Metadata.from(values)), Map.of(
        ContentMetadata.SCORE, 1.0d,
        ContentMetadata.RERANKED_SCORE, 1.0d));
  }
}
