ALTER TABLE knowledge_bases
  ADD COLUMN IF NOT EXISTS data_table_name varchar(80),
  ADD COLUMN IF NOT EXISTS data_schema_json text,
  ADD COLUMN IF NOT EXISTS data_row_count integer;

CREATE TABLE IF NOT EXISTS knowledge_base_data_tables (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL,
  doc_id bigint NOT NULL,
  physical_table_name varchar(80) NOT NULL UNIQUE,
  logical_name varchar(120) NOT NULL,
  description varchar(500),
  columns_json text NOT NULL,
  row_count integer NOT NULL,
  created_at timestamp(6) without time zone NOT NULL,
  updated_at timestamp(6) without time zone NOT NULL,
  CONSTRAINT fk_kb_data_table_doc FOREIGN KEY (doc_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_kb_data_tables_user_doc
  ON knowledge_base_data_tables(user_id, doc_id);

ALTER TABLE rag_evaluation_runs
  ADD COLUMN IF NOT EXISTS ndcg double precision,
  ADD COLUMN IF NOT EXISTS citation_hit_rate double precision,
  ADD COLUMN IF NOT EXISTS citation_coverage double precision;

CREATE TABLE IF NOT EXISTS rag_query_traces (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL,
  trace_id varchar(80) NOT NULL UNIQUE,
  question text NOT NULL,
  rewritten_question text,
  route_strategy varchar(40),
  route_reasoning varchar(500),
  knowledge_base_ids_json text,
  retrieved_json text,
  final_sources_json text,
  answer text,
  confidence double precision,
  invalid_citations_json text,
  latency_ms bigint,
  created_at timestamp(6) without time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rag_query_traces_user_created
  ON rag_query_traces(user_id, created_at DESC);
