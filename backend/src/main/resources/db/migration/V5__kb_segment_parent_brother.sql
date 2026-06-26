-- 知识库分段表新增父子/兄弟关系冗余列（从 metadata JSON 拆出建索引，供检索期 small-to-big 扩展高效查询）
-- 配合 InterviewElasticsearchContentRetriever 的 parent/brother 上下文扩展（对齐 know-engine）。

ALTER TABLE public.knowledge_base_segment ADD COLUMN IF NOT EXISTS parent_chunk_id character varying(64);
ALTER TABLE public.knowledge_base_segment ADD COLUMN IF NOT EXISTS brother_chunk_id character varying(64);
ALTER TABLE public.knowledge_base_segment ADD COLUMN IF NOT EXISTS brother_chunk_index integer;

CREATE INDEX IF NOT EXISTS idx_kbs_chunk_id  ON public.knowledge_base_segment (chunk_id);
CREATE INDEX IF NOT EXISTS idx_kbs_brother   ON public.knowledge_base_segment (brother_chunk_id);
