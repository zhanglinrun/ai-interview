ALTER TABLE rag_query_traces
  ADD COLUMN IF NOT EXISTS reranked_json text;
