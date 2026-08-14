package com.linrun.interview.rag.service;

import com.linrun.interview.document.entity.TableMetaEntity;
import com.linrun.interview.document.service.TableMetaCatalogService;
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
 * <p>静态表白名单 + {@code table_meta} 中用户 DATA_QUERY 动态表。</p>
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
  private final TableMetaCatalogService tableMetaCatalogService;
  private volatile Snapshot snapshot = new Snapshot("", Instant.EPOCH);

  public RagSqlSchemaService(DataSource dataSource,
                             KnowledgeBaseQueryProperties properties,
                             TableMetaCatalogService tableMetaCatalogService) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.properties = properties;
    this.tableMetaCatalogService = tableMetaCatalogService;
  }

  public String schemaDescription() {
    return schemaDescription(null);
  }

  public String schemaDescription(Long userId) {
    long ttl = Math.max(0L, properties.getMultiSource().getSql().getSchemaCacheSeconds());
    Snapshot current = snapshot;
    if (userId == null
        && !current.description().isBlank()
        && Instant.now().isBefore(current.expiresAt())) {
      return current.description();
    }
    synchronized (this) {
      if (userId == null) {
        current = snapshot;
        if (!current.description().isBlank() && Instant.now().isBefore(current.expiresAt())) {
          return current.description();
        }
      }
      String description = loadSchema(userId);
      if (userId == null) {
        snapshot = new Snapshot(description, Instant.now().plusSeconds(ttl));
      }
      return description;
    }
  }

  public Set<String> allowedTables() {
    return allowedTables(null);
  }

  public Set<String> allowedTables(Long userId) {
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
    if (userId != null) {
      for (TableMetaEntity meta : tableMetaCatalogService.listActiveForQuery(userId)) {
        if (meta.getTableName() != null && !meta.getTableName().isBlank()) {
          result.add(meta.getTableName().trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    return Set.copyOf(result);
  }

  private String loadSchema(Long userId) {
    Set<String> tables = allowedTables(userId);
    if (tables.isEmpty()) {
      return "无可用业务表；仅允许返回空结果。";
    }
    try {
      String catalog = loadCatalog(tables);
      if (!catalog.isBlank()) {
        catalog = appendDynamicTables(catalog, userId);
        return catalog.trim();
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
        return appendDynamicTables(staticDescription(tables), userId).trim();
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
      return appendDynamicTables(builder.toString().trim(), userId).trim();
    } catch (Exception e) {
      log.warn("读取 Text2SQL Schema 失败，使用静态白名单: {}", e.getMessage());
      return appendDynamicTables(staticDescription(tables), userId).trim();
    }
  }

  private String appendDynamicTables(String base, Long userId) {
    if (userId == null) {
      return base;
    }
    List<TableMetaEntity> dynamicTables = tableMetaCatalogService.listActiveForQuery(userId);
    if (dynamicTables.isEmpty()) {
      return base;
    }
    StringBuilder builder = new StringBuilder(base == null ? "" : base);
    for (TableMetaEntity meta : dynamicTables) {
      builder.append("\n\nTABLE ").append(meta.getTableName()).append("\n")
          .append(meta.getCreateSql());
      if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
        builder.append("\n说明：").append(meta.getDescription());
      }
    }
    return builder.toString();
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
      return "";
    }
  }

  private String staticDescription(Set<String> tables) {
    List<String> lines = new ArrayList<>();
    for (String table : tables) {
      if (table.startsWith("custom_data_query_")) {
        continue;
      }
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
