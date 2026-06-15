-- V2: user system and data isolation.
-- Old data is assigned to the default admin user with id = 1.

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(50),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_email ON users(email);

INSERT INTO users (id, username, email, password_hash, display_name, role, enabled, created_at)
VALUES (
    1,
    'admin',
    'admin@interview-guide.local',
    '$2a$10$ITjz94ki.PdJE90jdYJMrOkwaopW3yLJy73ZbFUkJvGg4.kLjosC.',
    'Default Admin',
    'ADMIN',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM users), 2), true);

ALTER TABLE resumes ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE resume_analyses ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE interview_sessions ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE interview_answers ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE rag_chat_sessions ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE agentic_interview_runs ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);
ALTER TABLE rag_evaluation_runs ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'voice_interview_sessions'
          AND column_name = 'user_id'
          AND data_type <> 'bigint'
    ) THEN
        ALTER TABLE voice_interview_sessions DROP COLUMN user_id;
    END IF;
END $$;

ALTER TABLE voice_interview_sessions
    ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 1 REFERENCES users(id);

ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_user_hash ON resumes(user_id, file_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_user_hash ON knowledge_bases(user_id, file_hash);

CREATE INDEX IF NOT EXISTS idx_resumes_user_id ON resumes(user_id);
CREATE INDEX IF NOT EXISTS idx_resume_analyses_user_id ON resume_analyses(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_user_id ON knowledge_bases(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_sessions_user_id ON interview_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_answers_user_id ON interview_answers(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_schedule_user_id ON interview_schedule(user_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_sessions_user_id ON rag_chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_voice_interview_sessions_user_id ON voice_interview_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_agentic_interview_runs_user_id ON agentic_interview_runs(user_id);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_runs_user_id ON rag_evaluation_runs(user_id);
