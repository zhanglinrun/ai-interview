-- 旧链路文档迁移：把 vectorStatus 映射到 docStatus
-- 注意：本项目开发环境用 ddl-auto=update，本脚本作为迁移文档，需手动执行。
UPDATE knowledge_bases SET docStatus = 'VECTOR_STORED' WHERE vectorStatus = 'COMPLETED' AND docStatus IS NULL;
UPDATE knowledge_bases SET docStatus = 'CHUNKED' WHERE vectorStatus = 'PROCESSING' AND docStatus IS NULL;
UPDATE knowledge_bases SET docStatus = 'CONVERTED' WHERE vectorStatus = 'PENDING' AND docStatus IS NULL;
UPDATE knowledge_bases SET docStatus = 'CHUNKED' WHERE vectorStatus = 'FAILED' AND docStatus IS NULL;

-- 旧字段清理（确认无依赖后手动执行，删除旧链路遗留字段）
-- ALTER TABLE knowledge_bases DROP COLUMN vectorStatus;
-- ALTER TABLE knowledge_bases DROP COLUMN vectorError;
-- ALTER TABLE knowledge_bases DROP COLUMN chunkCount;
