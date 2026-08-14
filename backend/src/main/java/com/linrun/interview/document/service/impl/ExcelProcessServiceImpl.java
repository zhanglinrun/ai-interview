package com.linrun.interview.document.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.TableMetaEntity;
import com.linrun.interview.document.mapper.TableMetaMapper;
import com.linrun.interview.document.service.ExcelProcessService;
import com.linrun.interview.document.service.FileProcessService;
import com.linrun.interview.document.vo.DocumentParseRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DATA_QUERY 专用 Excel/CSV 处理器：导入 MySQL 动态表 + table_meta。
 *
 * <p>多租户：物理表名 {@code custom_data_query_u{userId}_{逻辑名}}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelProcessServiceImpl implements FileProcessService, ExcelProcessService {

 private static final String TABLE_PREFIX = "custom_data_query_";
 private static final Pattern VALID_TABLE_NAME_PATTERN =
 Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

 private final TableMetaMapper tableMetaMapper;
 private final JdbcTemplate jdbcTemplate;
 private final TransactionTemplate transactionTemplate;
 private final ObjectMapper objectMapper;

 @Override
 public boolean supports(FileType fileType) {
 return supports(fileType, KnowledgeBaseType.DATA_QUERY);
 }

 @Override
 public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
 if (fileType != FileType.EXCEL && fileType != FileType.CSV) {
 return false;
 }
 return knowledgeBaseType == KnowledgeBaseType.DATA_QUERY;
 }

 @Override
 public String processDocument(byte[] fileBytes, String fileName) {
 throw new BusinessException(ErrorCode.BAD_REQUEST,
 "DATA_QUERY Excel 解析需要完整上下文，请使用 processDocument(DocumentParseRequest)");
 }

 @Override
 public String processDocument(DocumentParseRequest request) {
 if (request.userId() == null || request.versionId() == null) {
 throw new BusinessException(ErrorCode.BAD_REQUEST, "DATA_QUERY 解析缺少 userId 或 versionId");
 }
 try {
 importSpreadsheet(request);
 return null;
 } catch (BusinessException e) {
 throw e;
 } catch (Exception e) {
 throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
 "Excel/CSV 导入动态表失败: " + e.getMessage(), e);
 }
 }

 public String generatePhysicalTableName(Long userId, String originalFilename) {
 String baseName = originalFilename == null ? "table" : originalFilename;
 int dotIndex = baseName.lastIndexOf('.');
 if (dotIndex > 0) {
 baseName = baseName.substring(0, dotIndex);
 }
 baseName = sanitizeTableName(baseName);
 String userPrefix = "u" + userId + "_";
 int maxBaseLength = 64 - TABLE_PREFIX.length() - userPrefix.length();
 if (baseName.length() > maxBaseLength) {
 baseName = baseName.substring(0, maxBaseLength);
 }
 baseName = baseName.replaceAll("_+$", "");
 if (baseName.isEmpty()) {
 baseName = "table";
 }
 return TABLE_PREFIX + userPrefix + baseName;
 }

 public void dropTable(String tableName) {
 if (!isValidTableName(tableName)) {
 throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的表名: " + tableName);
 }
 transactionTemplate.executeWithoutResult(status -> {
 tableMetaMapper.dropTable(tableName);
 tableMetaMapper.physicalDeleteByTableName(tableName);
 });
 log.info("DATA_QUERY 动态表已删除: {}", tableName);
 }

 private void importSpreadsheet(DocumentParseRequest request) throws IOException {
 List<List<String>> excelData = parseSpreadsheet(
 request.fileBytes(), request.fileName());
 if (excelData.isEmpty() || excelData.size() < 2) {
 throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
 "Excel/CSV 为空或只有表头，没有数据行");
 }
 List<String> headers = excelData.getFirst();
 if (headers.isEmpty()) {
 throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "表头为空");
 }

 String tableName = generatePhysicalTableName(request.userId(), request.fileName());
 List<ColumnInfo> columns = generateColumnInfo(headers);
 boolean tableExists = tableMetaMapper.checkTableExists(tableName) > 0;

 if (tableExists) {
 TableMetaEntity existingMeta = tableMetaMapper.selectOne(
 Wrappers.<TableMetaEntity>lambdaQuery()
 .eq(TableMetaEntity::getTableName, tableName)
 .eq(TableMetaEntity::getUserId, request.userId()));
 if (existingMeta == null) {
 throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
 "表 " + tableName + " 元数据不存在");
 }
 List<ColumnInfo> existingColumns = parseColumnInfo(existingMeta.getColumnsInfo());
 if (!isSchemaCompatible(existingColumns, columns)) {
 throw new BusinessException(ErrorCode.BAD_REQUEST,
 "Excel 表结构与已有表 " + tableName + " 不一致，禁止上传");
 }
 deleteAllData(tableName);
 List<List<String>> dataRows = excelData.subList(1, excelData.size());
 insertData(tableName, columns, dataRows);
 existingMeta.setVersionId(request.versionId());
 existingMeta.setUpdatedAt(LocalDateTime.now());
 tableMetaMapper.updateById(existingMeta);
 } else {
 String createTableSql = generateCreateTableSql(tableName, "从 Excel 导入", columns);
 tableMetaMapper.executeCreateTable(createTableSql);
 List<List<String>> dataRows = excelData.subList(1, excelData.size());
 insertData(tableName, columns, dataRows);

 TableMetaEntity tableMeta = new TableMetaEntity();
 tableMeta.setUserId(request.userId());
 tableMeta.setTableName(tableName);
 tableMeta.setDescription("从 Excel 导入: " + request.fileName());
 tableMeta.setCreateSql(createTableSql);
 tableMeta.setColumnsInfo(objectMapper.writeValueAsString(columns));
 tableMeta.setVersionId(request.versionId());
 tableMeta.setCreatedAt(LocalDateTime.now());
 tableMeta.setUpdatedAt(LocalDateTime.now());
 tableMetaMapper.insert(tableMeta);
 }
 log.info("DATA_QUERY 表导入完成: table={}, versionId={}, rows={}",
 tableName, request.versionId(), excelData.size() - 1);
 }

 private List<List<String>> parseSpreadsheet(byte[] fileBytes, String fileName) throws IOException {
 String lower = fileName == null ? "" : fileName.toLowerCase();
 if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
 return parseDelimited(fileBytes, lower.endsWith(".tsv") ? '\t' : ',');
 }
 return parseExcel(fileBytes);
 }

 private List<List<String>> parseExcel(byte[] fileBytes) {
 List<List<String>> result = new ArrayList<>();
 EasyExcel.read(new ByteArrayInputStream(fileBytes), new ReadListener<Map<Integer, String>>() {
 @Override
 public void invoke(Map<Integer, String> data, AnalysisContext context) {
 List<String> row = new ArrayList<>();
 int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
 for (int i = 0; i <= maxIndex; i++) {
 String value = data.getOrDefault(i, "");
 row.add(value != null ? value : "");
 }
 result.add(row);
 }

 @Override
 public void doAfterAllAnalysed(AnalysisContext context) {
 log.debug("Excel 解析完成，共 {} 行", result.size());
 }
 }).headRowNumber(0).sheet().doRead();
 return result;
 }

 private List<List<String>> parseDelimited(byte[] fileBytes, char delimiter) {
 String text = decode(fileBytes);
 List<List<String>> rows = new ArrayList<>();
 List<String> row = new ArrayList<>();
 StringBuilder cell = new StringBuilder();
 boolean quoted = false;
 for (int i = 0; i < text.length(); i++) {
 char ch = text.charAt(i);
 if (ch == '"') {
 if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
 cell.append('"');
 i++;
 } else {
 quoted = !quoted;
 }
 } else if (ch == delimiter && !quoted) {
 row.add(cell.toString().trim());
 cell.setLength(0);
 } else if ((ch == '\n' || ch == '\r') && !quoted) {
 if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
 i++;
 }
 row.add(cell.toString().trim());
 if (row.stream().anyMatch(value -> value != null && !value.isBlank())) {
 rows.add(row);
 }
 row = new ArrayList<>();
 cell.setLength(0);
 } else {
 cell.append(ch);
 }
 }
 row.add(cell.toString().trim());
 if (row.stream().anyMatch(value -> value != null && !value.isBlank())) {
 rows.add(row);
 }
 return rows;
 }

 private String decode(byte[] bytes) {
 String text = new String(bytes, StandardCharsets.UTF_8);
 if (text.indexOf('\uFFFD') >= 0) {
 text = new String(bytes, Charset.forName("GB18030"));
 }
 return text.startsWith("\uFEFF") ? text.substring(1) : text;
 }

 private List<ColumnInfo> parseColumnInfo(String columnsInfoJson) throws IOException {
 if (columnsInfoJson == null || columnsInfoJson.isBlank()) {
 return Collections.emptyList();
 }
 return objectMapper.readValue(columnsInfoJson, new TypeReference<>() {});
 }

 private boolean isSchemaCompatible(List<ColumnInfo> existingColumns, List<ColumnInfo> newColumns) {
 if (existingColumns == null || newColumns == null) {
 return existingColumns == newColumns;
 }
 if (existingColumns.size() != newColumns.size()) {
 return false;
 }
 for (int i = 0; i < existingColumns.size(); i++) {
 ColumnInfo a = existingColumns.get(i);
 ColumnInfo b = newColumns.get(i);
 if (a == null || b == null) {
 return false;
 }
 if (!Objects.equals(a.columnName(), b.columnName())
 || !Objects.equals(a.dataType(), b.dataType())) {
 return false;
 }
 }
 return true;
 }

 private void deleteAllData(String tableName) {
 jdbcTemplate.execute("DELETE FROM `" + tableName + "`");
 }

 private String sanitizeTableName(String name) {
 if (name == null || name.trim().isEmpty()) {
 return "table";
 }
 String sanitized = name.toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");
 if (!sanitized.matches("^[a-zA-Z_].*")) {
 sanitized = "t_" + sanitized;
 }
 if (sanitized.length() > 40) {
 sanitized = sanitized.substring(0, 40);
 }
 return sanitized.replaceAll("_+$", "");
 }

 private boolean isValidTableName(String tableName) {
 return tableName != null && VALID_TABLE_NAME_PATTERN.matcher(tableName).matches();
 }

 private List<ColumnInfo> generateColumnInfo(List<String> headers) {
 List<ColumnInfo> columns = new ArrayList<>();
 Set<String> usedNames = new HashSet<>();
 for (int i = 0; i < headers.size(); i++) {
 String header = headers.get(i);
 String columnName = sanitizeColumnName(header);
 String originalName = columnName;
 int suffix = 1;
 while (usedNames.contains(columnName)) {
 columnName = originalName + "_" + suffix++;
 }
 usedNames.add(columnName);
 columns.add(new ColumnInfo(i, header, columnName, "VARCHAR(500)"));
 }
 return columns;
 }

 private String sanitizeColumnName(String name) {
 if (name == null || name.trim().isEmpty()) {
 return "col";
 }
 String sanitized = name.toLowerCase().trim().replaceAll("[^a-zA-Z0-9_]", "_");
 if (!sanitized.matches("^[a-zA-Z].*")) {
 sanitized = "col_" + sanitized;
 }
 if (sanitized.length() > 60) {
 sanitized = sanitized.substring(0, 60);
 }
 return sanitized.replaceAll("_+", "_").replaceAll("_+$", "");
 }

 private String generateCreateTableSql(String tableName, String description, List<ColumnInfo> columns) {
 StringBuilder sql = new StringBuilder();
 sql.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");
 sql.append(" `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',\n");
 for (ColumnInfo column : columns) {
 sql.append(" `").append(column.columnName()).append("` ")
 .append(column.dataType())
 .append(" DEFAULT NULL COMMENT '")
 .append(escapeSqlComment(column.originalHeader()))
 .append("',\n");
 }
 sql.append(" `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',\n");
 sql.append(" `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',\n");
 sql.append(" PRIMARY KEY (`id`)\n");
 sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='")
 .append(escapeSqlComment(description)).append("'");
 return sql.toString();
 }

 private String escapeSqlComment(String comment) {
 if (comment == null) {
 return "";
 }
 return comment.replace("'", "\\'").replace("\\", "\\\\");
 }

 private int insertData(String tableName, List<ColumnInfo> columns, List<List<String>> dataRows) {
 int batchSize = 500;
 int totalInserted = 0;
 for (int i = 0; i < dataRows.size(); i += batchSize) {
 List<List<String>> batch = dataRows.subList(i, Math.min(i + batchSize, dataRows.size()));
 jdbcTemplate.execute(generateBatchInsertSql(tableName, columns, batch));
 totalInserted += batch.size();
 }
 return totalInserted;
 }

 private String generateBatchInsertSql(String tableName, List<ColumnInfo> columns, List<List<String>> rows) {
 String columnNames = columns.stream()
 .map(column -> "`" + column.columnName() + "`")
 .collect(Collectors.joining(", "));
 StringBuilder sql = new StringBuilder();
 sql.append("INSERT INTO `").append(tableName).append("` (").append(columnNames).append(") VALUES ");
 for (int i = 0; i < rows.size(); i++) {
 List<String> row = rows.get(i);
 if (i > 0) {
 sql.append(", ");
 }
 sql.append("(");
 for (int j = 0; j < columns.size(); j++) {
 if (j > 0) {
 sql.append(", ");
 }
 String value = j < row.size() ? row.get(j) : "";
 sql.append(escapeSqlValue(value));
 }
 sql.append(")");
 }
 return sql.toString();
 }

 private String escapeSqlValue(String value) {
 if (value == null || value.isEmpty()) {
 return "NULL";
 }
 String escaped = value.replace("'", "''")
 .replace("\\", "\\\\")
 .replace("\n", "\\n")
 .replace("\r", "\\r")
 .replace("\t", "\\t");
 return "'" + escaped + "'";
 }

 public record ColumnInfo(int index, String originalHeader, String columnName, String dataType) {}
}
