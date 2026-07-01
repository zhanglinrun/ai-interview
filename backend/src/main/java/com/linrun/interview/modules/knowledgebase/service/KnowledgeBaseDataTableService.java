package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseDataTableMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseDataTableEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.service.parse.SpreadsheetProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDataTableService {

  private static final int MAX_COLUMNS = 40;
  private static final int MAX_CELL_CHARS = 1000;
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

  private final JdbcTemplate jdbcTemplate;
  private final KnowledgeBaseDataTableMapper dataTableMapper;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseDataTableEntity createForSpreadsheet(
      KnowledgeBaseEntity entity,
      SpreadsheetProcessService.ParsedSpreadsheet spreadsheet) {
    if (spreadsheet == null || spreadsheet.headers().isEmpty()) {
      return null;
    }
    List<ColumnDef> columns = normalizeColumns(spreadsheet.headers());
    if (columns.isEmpty()) {
      return null;
    }
    String tableName = physicalTableName(entity.getUserId(), entity.getId());
    dropTable(tableName);
    findByUserAndDoc(entity.getUserId(), entity.getId()).ifPresent(existing -> {
      dataTableMapper.deleteById(existing.getId());
    });
    createTable(tableName, columns);
    insertRows(tableName, entity.getUserId(), entity.getId(), columns, spreadsheet.rows());

    LocalDateTime now = LocalDateTime.now();
    KnowledgeBaseDataTableEntity saved = MapperUtils.save(dataTableMapper, KnowledgeBaseDataTableEntity.builder()
      .userId(entity.getUserId())
      .docId(entity.getId())
      .physicalTableName(tableName)
      .logicalName(entity.getName())
      .description(entity.getDescription())
      .columnsJson(toJson(columns))
      .rowCount(spreadsheet.rows().size())
      .createdAt(now)
      .updatedAt(now)
      .build());
    log.info("动态数据表已创建: docId={}, table={}, rows={}",
        entity.getId(), tableName, spreadsheet.rows().size());
    return saved;
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteByDoc(Long userId, Long docId) {
    findByUserAndDoc(userId, docId).ifPresent(table -> {
      dropTable(table.getPhysicalTableName());
      dataTableMapper.deleteById(table.getId());
      log.info("动态数据表已删除: docId={}, table={}", docId, table.getPhysicalTableName());
    });
  }

  public List<KnowledgeBaseDataTableEntity> listByUser(Long userId) {
    return dataTableMapper.selectList(
      Wrappers.<KnowledgeBaseDataTableEntity>lambdaQuery()
        .eq(KnowledgeBaseDataTableEntity::getUserId, userId));
  }

  public String databaseStructure(Long userId) {
    String dynamicTables = listByUser(userId).stream()
      .map(this::formatTable)
      .collect(Collectors.joining("\n\n"));
    if (dynamicTables.isBlank()) {
      return "";
    }
    return "\n\n用户导入的面试结构化数据表（只读，适合投递记录、面试反馈、题库统计、错题记录等查询，"
        + "均自动包含 user_id/doc_id）：\n" + dynamicTables;
  }

  public Set<String> allowedDynamicTables(Long userId) {
    return listByUser(userId).stream()
      .map(KnowledgeBaseDataTableEntity::getPhysicalTableName)
      .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public PreviewResponse preview(Long userId, Long docId, int page, int size) {
    KnowledgeBaseDataTableEntity table = findByUserAndDoc(userId, docId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该知识库没有结构化数据表"));
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(size, 1), 100);
    int offset = (safePage - 1) * safeSize;
    List<ColumnDef> columns = parseColumns(table.getColumnsJson());
    List<String> selectColumns = new ArrayList<>();
    selectColumns.add("id");
    columns.stream().map(ColumnDef::name).forEach(selectColumns::add);
    String sql = "SELECT " + selectColumns.stream().map(this::quote).collect(Collectors.joining(", "))
      + " FROM " + quote(table.getPhysicalTableName())
      + " WHERE user_id = ? AND doc_id = ? ORDER BY id ASC LIMIT ? OFFSET ?";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, docId, safeSize, offset);
    return new PreviewResponse(table.getPhysicalTableName(), table.getLogicalName(), table.getRowCount(),
        safePage, safeSize, columns, rows);
  }

  private Optional<KnowledgeBaseDataTableEntity> findByUserAndDoc(Long userId, Long docId) {
    return Optional.ofNullable(dataTableMapper.selectOne(
      Wrappers.<KnowledgeBaseDataTableEntity>lambdaQuery()
        .eq(KnowledgeBaseDataTableEntity::getUserId, userId)
        .eq(KnowledgeBaseDataTableEntity::getDocId, docId)));
  }

  private String formatTable(KnowledgeBaseDataTableEntity table) {
    List<ColumnDef> columns = parseColumns(table.getColumnsJson());
    StringBuilder sb = new StringBuilder("Table ").append(table.getPhysicalTableName()).append(":\n")
      .append("- id bigint primary key\n")
      .append("- user_id bigint\n")
      .append("- doc_id bigint\n");
    for (ColumnDef column : columns) {
      sb.append("- ").append(column.name()).append(" text");
      if (!column.title().equals(column.name())) {
        sb.append(" -- ").append(column.title());
      }
      sb.append('\n');
    }
    return sb.append("-- ").append(table.getLogicalName())
      .append("，共 ").append(table.getRowCount()).append(" 行").toString();
  }

  private void createTable(String tableName, List<ColumnDef> columns) {
    String columnSql = columns.stream()
      .map(column -> quote(column.name()) + " text")
      .collect(Collectors.joining(",\n  "));
    jdbcTemplate.execute("""
        CREATE TABLE %s (
          id bigserial PRIMARY KEY,
          user_id bigint NOT NULL,
          doc_id bigint NOT NULL,
          %s
        )
        """.formatted(quote(tableName), columnSql));
    jdbcTemplate.execute("CREATE INDEX idx_" + tableName + "_user_doc ON "
      + quote(tableName) + " (user_id, doc_id)");
  }

  private void insertRows(String tableName, Long userId, Long docId,
                          List<ColumnDef> columns, List<List<String>> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    String names = columns.stream().map(ColumnDef::name).map(this::quote).collect(Collectors.joining(", "));
    String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
    String sql = "INSERT INTO " + quote(tableName) + " (user_id, doc_id, " + names + ") VALUES (?, ?, "
      + placeholders + ")";
    jdbcTemplate.batchUpdate(sql, rows, 100, (PreparedStatement ps, List<String> row) -> {
      ps.setLong(1, userId);
      ps.setLong(2, docId);
      for (int i = 0; i < columns.size(); i++) {
        String value = i < row.size() ? row.get(i) : "";
        ps.setString(i + 3, truncate(value));
      }
    });
  }

  private void dropTable(String tableName) {
    if (!SAFE_IDENTIFIER.matcher(tableName).matches()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "非法数据表名: " + tableName);
    }
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + quote(tableName));
  }

  private String physicalTableName(Long userId, Long docId) {
    return "kb_data_u_" + userId + "_" + docId;
  }

  private List<ColumnDef> normalizeColumns(List<String> headers) {
    List<ColumnDef> columns = new ArrayList<>();
    Map<String, Integer> used = new LinkedHashMap<>();
    int limit = Math.min(headers.size(), MAX_COLUMNS);
    for (int i = 0; i < limit; i++) {
      String title = headers.get(i) == null || headers.get(i).isBlank()
        ? "列" + (i + 1)
        : headers.get(i).trim();
      String base = normalizeIdentifier(title);
      int count = used.getOrDefault(base, 0);
      used.put(base, count + 1);
      String name = count == 0 ? base : base + "_" + (count + 1);
      columns.add(new ColumnDef(name, title));
    }
    return columns;
  }

  private String normalizeIdentifier(String raw) {
    String normalized = raw.toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "_")
      .replaceAll("_+", "_")
      .replaceAll("^_|_$", "");
    if (normalized.isBlank() || Character.isDigit(normalized.charAt(0))) {
      normalized = "col_" + normalized;
    }
    if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
      normalized = "col";
    }
    return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
  }

  private String truncate(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= MAX_CELL_CHARS ? value : value.substring(0, MAX_CELL_CHARS);
  }

  private String quote(String identifier) {
    if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "非法 SQL 标识符: " + identifier);
    }
    return "\"" + identifier + "\"";
  }

  private String toJson(List<ColumnDef> columns) {
    try {
      return objectMapper.writeValueAsString(columns);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "序列化表结构失败", e);
    }
  }

  private List<ColumnDef> parseColumns(String json) {
    try {
      return objectMapper.readValue(json,
        objectMapper.getTypeFactory().constructCollectionType(List.class, ColumnDef.class));
    } catch (Exception e) {
      log.warn("解析动态表列定义失败: {}", e.getMessage(), e);
      return List.of();
    }
  }

  public record ColumnDef(String name, String title) {}

  public record PreviewResponse(
      String tableName,
      String logicalName,
      int total,
      int page,
      int size,
      List<ColumnDef> columns,
      List<Map<String, Object>> rows
  ) {}
}
