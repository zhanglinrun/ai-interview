-- ============================================================================
-- 升级脚本：2026-07（能力原子画像）
--
-- 适用对象：已初始化过 candidate_memory 的存量库。全新库由 schema.sql 直接建表。
-- 新增逐题能力原子、评估分和证据 ID 字段，并用 session_id + question_index 保证幂等。
-- 每条 DDL 都先检查 information_schema，可重复执行。
-- ============================================================================

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD COLUMN `capability_atom_id` VARCHAR(191) NULL AFTER `skill_id`',
    'SELECT ''capability_atom_id 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND COLUMN_NAME = 'capability_atom_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD COLUMN `question_index` INT NULL AFTER `kind`',
    'SELECT ''question_index 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND COLUMN_NAME = 'question_index');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD COLUMN `mastery_score` INT NULL AFTER `question_index`',
    'SELECT ''mastery_score 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND COLUMN_NAME = 'mastery_score');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD COLUMN `evidence_ids_json` TEXT NULL AFTER `evidence`',
    'SELECT ''evidence_ids_json 已存在，跳过'' AS message')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND COLUMN_NAME = 'evidence_ids_json');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD INDEX `idx_candidate_memory_user_atom` (`user_id`, `capability_atom_id`, `created_at` DESC)',
    'SELECT ''idx_candidate_memory_user_atom 已存在，跳过'' AS message')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND INDEX_NAME = 'idx_candidate_memory_user_atom');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `candidate_memory` ADD UNIQUE INDEX `uk_candidate_memory_session_question` (`session_id`, `question_index`)',
    'SELECT ''uk_candidate_memory_session_question 已存在，跳过'' AS message')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'candidate_memory'
    AND INDEX_NAME = 'uk_candidate_memory_session_question');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '2026-07 升级脚本执行完成（能力原子画像）' AS done;
