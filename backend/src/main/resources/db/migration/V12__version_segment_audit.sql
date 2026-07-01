-- P1-8: 版本/分段表补齐乐观锁与逻辑删除字段（对齐 know-engine BaseEntity）
ALTER TABLE knowledge_base_version ADD COLUMN IF NOT EXISTS lock_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_base_version ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE knowledge_base_segment ADD COLUMN IF NOT EXISTS lock_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_base_segment ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
