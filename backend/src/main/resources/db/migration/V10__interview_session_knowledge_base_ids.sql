ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS knowledge_base_ids_json TEXT;
