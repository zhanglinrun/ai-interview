CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm 提供三元组相似度（word_similarity），用于混合检索的关键词通道。
-- 选择 pg_trgm 而非 to_tsvector 全文检索：pgvector/pgvector:pg16 镜像不含中文分词扩展，
-- to_tsvector('simple', ...) 无法对无空格的中文正确切词，而 pg_trgm 对中英文混合文本都稳定可用。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
