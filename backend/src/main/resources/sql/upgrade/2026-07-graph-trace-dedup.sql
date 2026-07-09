-- ============================================================================
-- 升级脚本：2026-07（图谱 Trace 列 + 知识库用户级去重索引）
--
-- 适用对象：在本次升级前已初始化过数据的存量库。
--   schema.sql 只在空库初始化时执行（CREATE TABLE IF NOT EXISTS 不会修改已有表），
--   存量库需手动执行本脚本。全新库（或删卷重建）无需执行。
--
-- 不执行的后果：
--   1) rag_query_traces 缺 graph_attempted/graph_hit/graph_result 三列时，
--      RAG Trace 落库 INSERT 报「Unknown column」——RagQueryTraceService.save 有
--      try-catch 不会影响问答主流程，但 Trace 会静默丢失（只剩 warn 日志）。
--   2) knowledge_base_version 缺 idx_kbv_upload_user_hash 时功能不受影响，
--      仅按用户去重查询（upload_user + content_hash）退化为全表扫描。
--
-- 幂等性：每条 DDL 先查 information_schema，已存在则跳过；重复执行安全。
-- 执行方式（本机 dev compose 示例，端口/密码以 .env 为准）：
--   mysql -h127.0.0.1 -P33306 -uai_interview -p ai_interview \
--     < backend/src/main/resources/sql/upgrade/2026-07-graph-trace-dedup.sql
-- ============================================================================

-- 1) rag_query_traces.graph_attempted（图谱是否尝试）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `rag_query_traces` ADD COLUMN `graph_attempted` TINYINT NULL AFTER `crag_action`',
    'SELECT ''graph_attempted 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rag_query_traces'
    AND COLUMN_NAME = 'graph_attempted');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) rag_query_traces.graph_hit（图谱是否命中）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `rag_query_traces` ADD COLUMN `graph_hit` TINYINT NULL AFTER `graph_attempted`',
    'SELECT ''graph_hit 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rag_query_traces'
    AND COLUMN_NAME = 'graph_hit');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) rag_query_traces.graph_result（图谱命中时的 Cypher 结果片段）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `rag_query_traces` ADD COLUMN `graph_result` TEXT NULL AFTER `graph_hit`',
    'SELECT ''graph_result 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rag_query_traces'
    AND COLUMN_NAME = 'graph_result');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) knowledge_base_version 用户级去重索引（upload_user + content_hash）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `knowledge_base_version` ADD INDEX `idx_kbv_upload_user_hash` (`upload_user`, `content_hash`)',
    'SELECT ''idx_kbv_upload_user_hash 已存在，跳过'' AS message')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'knowledge_base_version'
    AND INDEX_NAME = 'idx_kbv_upload_user_hash');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '2026-07 升级脚本执行完成（图谱 Trace 列 + 去重索引）' AS done;
