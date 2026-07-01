-- P0/P1/P2: 文档权限、到期日、乐观锁与逻辑删除（对齐 know-engine BaseEntity）
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS accessible_by VARCHAR(32) DEFAULT 'PRIVATE';
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS expire_date DATE;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS lock_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_kb_expire_date ON knowledge_bases (expire_date)
  WHERE expire_date IS NOT NULL;

COMMENT ON COLUMN knowledge_bases.accessible_by IS 'PRIVATE=仅所有者, PUBLIC=同租户可读';
COMMENT ON COLUMN knowledge_bases.expire_date IS '到期后不参与检索';
