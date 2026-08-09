package com.linrun.interview.rag.service;

import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text2SQL 的 Schema 目录。
 *
 * <p>优先读取配置的表白名单，再从 {@code information_schema.columns} 获取列信息；
 * 查询失败时返回静态安全描述，避免 Schema 探测故障阻断 ES 问答。表名始终来自白名单，
 * 不把数据库中所有表暴露给模型。</p>
 */
@Slf4j
@Service
public class RagSqlSchemaService {

  private static final List<String> DEFAULT_TABLES = List.of(
      "documents", "document_versions", "document_segments",
      "resumes", "resume_analyses", "interview_sessions", "interview_answers",
      "chat_sessions", "chat_messages", "chat_memories");

  private final JdbcTemplate jdbcTemplate;
  private final KnowledgeBaseQueryProperties properties;
  private volatile Snapshot snapshot = new Snapshot("", Instant.EPOCH);

  public RagSqlSchemaService(DataSource dataSource, KnowledgeBaseQueryProperties properties) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.properties = properties;
  }

  public String schemaDescription() {
    long ttl = Math.max(0L, properties.getMultiSource().getSql().getSchemaCacheSeconds());
    Snapshot current = snapshot;
    if (!current.description().isBlank()
        && Instant.now().isBefore(current.expiresAt())) {
      return current.description();
    }
    synchronized (this) {
      current = snapshot;
      if (!current.description().isBlank() && Instant.now().isBefore(current.expiresAt())) {
        return current.description();
      }
      String description = loadSchema();
      snapshot = new Snapshot(description, Instant.now().plusSeconds(ttl));
      return description;
    }
  }

  public Set<String> allowedTables() {
    List<String> configured = properties.getMultiSource().getSql().getAllowedTables();
    List<String> tables = configured == null || configured.isEmpty() ? DEFAULT_TABLES : configured;
    Set<String> result = new LinkedHashSet<>();
    for (String table : tables) {
      if (table == null || table.isBlank()) {
        continue;
      }
      String normalized = table.trim().toLowerCase(Locale.ROOT);
      if (normalized.matches("[a-z][a-z0-9_]{0,63}")) {
        result.add(normalized);
      }
    }
    return Set.copyOf(result);
  }

  private String loadSchema() {
    Set<String> tables = allowedTables();
    if (tables.isEmpty()) {
      return "无可用业务表；仅允许返回空结果。";
    }
    try {
      String catalog = loadCatalog(tables);
      if (!catalog.isBlank()) {
        return catalog;
      }
      String placeholders = String.join(",", tables.stream().map(t -> "?").toList());
      String sql = "SELECT table_name, column_name, data_type "
          + "FROM information_schema.columns "
          + "WHERE table_schema = DATABASE() AND table_name IN (" + placeholders + ") "
          + "ORDER BY table_name, ordinal_position";
      List<Column> columns = jdbcTemplate.query(sql, tables.toArray(),
          (rs, rowNum) -> new Column(rs.getString("table_name"),
              rs.getString("column_name"), rs.getString("data_type")));
      if (columns.isEmpty()) {
        return staticDescription(tables);
      }
      StringBuilder builder = new StringBuilder();
      String currentTable = null;
      for (Column column : columns) {
        if (!column.table().equals(currentTable)) {
          if (currentTable != null) {
            builder.append("\n");
          }
          currentTable = column.table();
          builder.append("TABLE ").append(currentTable).append(" (\n");
        }
        builder.append("  ").append(column.name()).append(" ").append(column.type()).append(",\n");
      }
      if (currentTable != null) {
        builder.append(")\n");
      }
      return builder.toString().trim();
    } catch (Exception e) {
      log.warn("读取 Text2SQL Schema 失败，使用静态白名单: {}", e.getMessage());
      return staticDescription(tables);
    }
  }

  private String loadCatalog(Set<String> tables) {
    try {
      String placeholders = String.join(",", tables.stream().map(t -> "?").toList());
      String sql = "SELECT table_name, schema_ddl, description FROM rag_table_meta "
          + "WHERE enabled = 1 AND table_name IN (" + placeholders + ") ORDER BY table_name";
      List<CatalogRow> rows = jdbcTemplate.query(sql, tables.toArray(),
          (rs, rowNum) -> new CatalogRow(rs.getString("table_name"),
              rs.getString("schema_ddl"), rs.getString("description")));
      if (rows.isEmpty()) {
        return "";
      }
      StringBuilder result = new StringBuilder();
      for (CatalogRow row : rows) {
        result.append("TABLE ").append(row.tableName()).append("\n")
            .append(row.schemaDdl()).append("\n");
        if (row.description() != null && !row.description().isBlank()) {
          result.append("说明：").append(row.description()).append("\n");
        }
        result.append("\n");
      }
      return result.toString().trim();
    } catch (Exception e) {
      // 元数据目录不可用时，回退 information_schema，不阻断启动/查询。
      return "";
    }
  }

  private String staticDescription(Set<String> tables) {
    List<String> lines = new ArrayList<>();
    for (String table : tables) {
      lines.add("TABLE " + table + " (user_id BIGINT, id BIGINT, created_at DATETIME, updated_at DATETIME)");
    }
    return String.join("\n", lines);
  }

  private record Snapshot(String description, Instant expiresAt) {
  }

  private record Column(String table, String name, String type) {
  }

  private record CatalogRow(String tableName, String schemaDdl, String description) {
  }
}
