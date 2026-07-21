-- MySQL 8.0 可重复执行升级脚本：information_schema 判定后再执行 DDL。
-- 避免不同 8.0 小版本对 ADD COLUMN IF NOT EXISTS 支持差异，以及重复创建索引失败。

SET @schema_name = DATABASE();

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND COLUMN_NAME = 'embedding_attempt'
    ),
    'SELECT 1',
    'ALTER TABLE `knowledge_base_version` ADD COLUMN `embedding_attempt` INT NOT NULL DEFAULT 0 AFTER `status`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND COLUMN_NAME = 'embedding_claimed_at'
    ),
    'SELECT 1',
    'ALTER TABLE `knowledge_base_version` ADD COLUMN `embedding_claimed_at` DATETIME(6) NULL AFTER `embedding_attempt`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND COLUMN_NAME = 'embedding_next_retry_at'
    ),
    'SELECT 1',
    'ALTER TABLE `knowledge_base_version` ADD COLUMN `embedding_next_retry_at` DATETIME(6) NULL AFTER `embedding_claimed_at`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND COLUMN_NAME = 'embedding_last_error'
    ),
    'SELECT 1',
    'ALTER TABLE `knowledge_base_version` ADD COLUMN `embedding_last_error` VARCHAR(1000) NULL AFTER `embedding_next_retry_at`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND COLUMN_NAME = 'embedding_terminal_failure'
    ),
    'SELECT 1',
    'ALTER TABLE `knowledge_base_version` ADD COLUMN `embedding_terminal_failure` TINYINT(1) NOT NULL DEFAULT 0 AFTER `embedding_last_error`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'knowledge_base_version'
          AND INDEX_NAME = 'idx_kbv_embedding_recovery'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_kbv_embedding_recovery` ON `knowledge_base_version` (`status`, `embedding_terminal_failure`, `embedding_next_retry_at`, `embedding_claimed_at`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
