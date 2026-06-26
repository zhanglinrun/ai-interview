-- 知识库版本号唯一约束：同一文档下 version 字段不可重复（防止并发上传新版本产生重复版本号）
-- 原 V3 仅建普通联合索引 idx_kbv_doc_version，这里改为唯一约束。

DROP INDEX IF EXISTS public.idx_kbv_doc_version;

ALTER TABLE public.knowledge_base_version
    DROP CONSTRAINT IF EXISTS uk_kbv_doc_version;

ALTER TABLE public.knowledge_base_version
    ADD CONSTRAINT uk_kbv_doc_version UNIQUE (doc_id, version);
