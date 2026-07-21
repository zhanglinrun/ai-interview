-- RAG 检索评测指标准确命名；保留 citation_* 旧列用于历史兼容。

SET @schema_name = DATABASE();

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'rag_evaluation_runs'
          AND COLUMN_NAME = 'retrieval_recall'
    ),
    'SELECT 1',
    'ALTER TABLE `rag_evaluation_runs` ADD COLUMN `retrieval_recall` DOUBLE NULL AFTER `ndcg`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'rag_evaluation_runs'
          AND COLUMN_NAME = 'retrieval_precision'
    ),
    'SELECT 1',
    'ALTER TABLE `rag_evaluation_runs` ADD COLUMN `retrieval_precision` DOUBLE NULL AFTER `retrieval_recall`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
