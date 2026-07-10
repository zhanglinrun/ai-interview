-- AI Interview Platform MySQL Schema (converted from Flyway V1-V10, aligned with industry practice)
-- Charset: utf8mb4

SET NAMES utf8mb4;

-- ==================== 用户 ====================
CREATE TABLE IF NOT EXISTS `users` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(50)  NOT NULL,
    `email`           VARCHAR(100) NOT NULL,
    `password_hash`   VARCHAR(100) NOT NULL,
    `display_name`    VARCHAR(50)  NULL,
    `role`            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `last_login_at`   DATETIME(6)  NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_username` (`username`),
    KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `users` (`id`, `username`, `email`, `password_hash`, `display_name`, `role`, `enabled`, `created_at`)
VALUES (1, 'admin', 'admin@ai-interview.local',
        '$2a$10$ITjz94ki.PdJE90jdYJMrOkwaopW3yLJy73ZbFUkJvGg4.kLjosC.',
        'Default Admin', 'ADMIN', 1, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ==================== 简历 ====================
CREATE TABLE IF NOT EXISTS `resumes` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT       NOT NULL DEFAULT 1,
    `file_hash`          VARCHAR(64)  NOT NULL,
    `original_filename`  VARCHAR(255) NOT NULL,
    `file_size`          BIGINT       NULL,
    `content_type`       VARCHAR(255) NULL,
    `storage_key`        VARCHAR(500) NULL,
    `storage_url`        VARCHAR(1000) NULL,
    `resume_text`        TEXT         NULL,
    `uploaded_at`        DATETIME(6)  NOT NULL,
    `last_accessed_at`   DATETIME(6)  NULL,
    `access_count`       INT          NULL DEFAULT 0,
    `analyze_status`     VARCHAR(20)  NULL,
    `analyze_error`      VARCHAR(500) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_resume_user_hash` (`user_id`, `file_hash`),
    KEY `idx_resumes_user_id` (`user_id`),
    CONSTRAINT `fk_resumes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `resume_analyses` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT      NOT NULL DEFAULT 1,
    `resume_id`          BIGINT      NOT NULL,
    `analyzed_at`        DATETIME(6) NOT NULL,
    `content_score`      INT         NULL,
    `expression_score`   INT         NULL,
    `overall_score`      INT         NULL,
    `project_score`      INT         NULL,
    `skill_match_score`  INT         NULL,
    `structure_score`    INT         NULL,
    `strengths_json`     TEXT        NULL,
    `suggestions_json`   TEXT        NULL,
    `summary`            TEXT        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_resume_analyses_user_id` (`user_id`),
    KEY `idx_resume_analyses_resume_id` (`resume_id`),
    CONSTRAINT `fk_resume_analyses_resume` FOREIGN KEY (`resume_id`) REFERENCES `resumes` (`id`),
    CONSTRAINT `fk_resume_analyses_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 知识库 ====================
CREATE TABLE IF NOT EXISTS `knowledge_bases` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`              BIGINT       NOT NULL DEFAULT 1,
    `file_hash`            VARCHAR(64)  NOT NULL,
    `name`                 VARCHAR(255) NOT NULL,
    `category`             VARCHAR(100) NULL,
    `original_filename`    VARCHAR(255) NOT NULL,
    `file_size`            BIGINT       NULL,
    `content_type`         VARCHAR(255) NULL,
    `storage_key`          VARCHAR(500) NULL,
    `storage_url`          VARCHAR(1000) NULL,
    `uploaded_at`          DATETIME(6)  NOT NULL,
    `last_accessed_at`     DATETIME(6)  NULL,
    `access_count`         INT          NULL DEFAULT 0,
    `question_count`       INT          NULL DEFAULT 0,
    `current_version_id`   BIGINT       NULL,
    `description`          VARCHAR(500) NULL,
    `doc_status`           VARCHAR(20)  NULL DEFAULT 'INIT',
    `data_table_name`      VARCHAR(80)  NULL,
    `data_schema_json`     TEXT         NULL,
    `data_row_count`       INT          NULL,
    `knowledge_base_type`  VARCHAR(32)  NULL DEFAULT 'DOCUMENT_SEARCH',
    `accessible_by`        VARCHAR(32)  NULL DEFAULT 'PRIVATE',
    `expire_date`          DATE         NULL,
    `lock_version`         INT          NOT NULL DEFAULT 0,
    `deleted`              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_kb_user_hash` (`user_id`, `file_hash`),
    KEY `idx_kb_category` (`category`),
    KEY `idx_knowledge_bases_user_id` (`user_id`),
    CONSTRAINT `fk_knowledge_bases_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_base_version` (
    `version_id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `doc_id`             BIGINT       NOT NULL,
    `version`            VARCHAR(32)  NOT NULL,
    `doc_url`            VARCHAR(1000) NULL,
    `converted_doc_url`  VARCHAR(1000) NULL,
    `converted_content`  LONGTEXT     NULL,
    `content_hash`       VARCHAR(64)  NULL,
    `status`             VARCHAR(20)  NULL,
    `upload_user`        VARCHAR(64)  NULL,
    `changelog`          VARCHAR(500) NULL,
    `created_at`         DATETIME(6)  NOT NULL,
    `updated_at`         DATETIME(6)  NULL,
    `lock_version`       INT          NOT NULL DEFAULT 0,
    `deleted`            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_kbv_doc_version` (`doc_id`, `version`),
    KEY `idx_kbv_doc_id` (`doc_id`),
    KEY `idx_kbv_upload_user_hash` (`upload_user`, `content_hash`),
    CONSTRAINT `fk_kbv_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_bases` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_base_segment` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `text`                TEXT         NOT NULL,
    `chunk_id`            VARCHAR(64)  NULL,
    `parent_chunk_id`     VARCHAR(64)  NULL,
    `brother_chunk_id`    VARCHAR(64)  NULL,
    `brother_chunk_index` INT          NULL,
    `metadata`            TEXT         NULL,
    `document_id`         BIGINT       NOT NULL,
    `document_version`    BIGINT       NOT NULL,
    `chunk_order`         INT          NULL,
    `embedding_id`        VARCHAR(128) NULL,
    `status`              VARCHAR(20)  NULL,
    `skip_embedding`      INT          NULL DEFAULT 0,
    `created_at`          DATETIME(6)  NOT NULL,
    `updated_at`          DATETIME(6)  NULL,
    `lock_version`        INT          NOT NULL DEFAULT 0,
    `deleted`             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_kbs_doc_version` (`document_id`, `document_version`),
    KEY `idx_kbs_status` (`status`),
    KEY `idx_kbs_chunk_id` (`chunk_id`),
    KEY `idx_kbs_brother` (`brother_chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_base_data_tables` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`              BIGINT       NOT NULL,
    `doc_id`               BIGINT       NOT NULL,
    `physical_table_name`  VARCHAR(80)  NOT NULL,
    `logical_name`         VARCHAR(120) NOT NULL,
    `description`          VARCHAR(500) NULL,
    `columns_json`         TEXT         NOT NULL,
    `row_count`            INT          NOT NULL,
    `created_at`           DATETIME(6)  NOT NULL,
    `updated_at`           DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_physical_table_name` (`physical_table_name`),
    KEY `idx_kb_data_tables_user_doc` (`user_id`, `doc_id`),
    CONSTRAINT `fk_kb_data_table_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_bases` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== RAG 聊天 ====================
CREATE TABLE IF NOT EXISTS `rag_chat_sessions` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL DEFAULT 1,
    `title`          VARCHAR(255) NOT NULL,
    `status`         VARCHAR(20)  NULL,
    `created_at`     DATETIME(6)  NOT NULL,
    `updated_at`     DATETIME(6)  NULL,
    `message_count`  INT          NULL DEFAULT 0,
    `is_pinned`      TINYINT(1)   NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_rag_session_updated` (`updated_at`),
    KEY `idx_rag_chat_sessions_user_id` (`user_id`),
    CONSTRAINT `fk_rag_chat_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_chat_messages` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`         BIGINT      NOT NULL,
    `type`               VARCHAR(20) NOT NULL,
    `content`            TEXT        NOT NULL,
    `transform_content`  TEXT        NULL,
    `message_order`      INT         NOT NULL,
    `created_at`         DATETIME(6) NOT NULL,
    `updated_at`         DATETIME(6) NULL,
    `completed`          TINYINT(1)  NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    KEY `idx_rag_message_session` (`session_id`),
    KEY `idx_rag_message_order` (`session_id`, `message_order`),
    CONSTRAINT `fk_rag_chat_messages_session` FOREIGN KEY (`session_id`) REFERENCES `rag_chat_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_session_knowledge_bases` (
    `session_id`         BIGINT NOT NULL,
    `knowledge_base_id`  BIGINT NOT NULL,
    PRIMARY KEY (`session_id`, `knowledge_base_id`),
    KEY `idx_rskb_kb_id` (`knowledge_base_id`),
    CONSTRAINT `fk_rskb_session` FOREIGN KEY (`session_id`) REFERENCES `rag_chat_sessions` (`id`),
    CONSTRAINT `fk_rskb_kb` FOREIGN KEY (`knowledge_base_id`) REFERENCES `knowledge_bases` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_evaluation_runs` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`                 BIGINT        NOT NULL DEFAULT 1,
    `run_id`                  VARCHAR(80)   NOT NULL,
    `title`                   VARCHAR(120)  NOT NULL,
    `knowledge_base_ids_json` TEXT          NULL,
    `cases_json`              TEXT          NULL,
    `total_cases`             INT           NULL,
    `hit_count`               INT           NULL,
    `hit_rate`                DOUBLE        NULL,
    `mean_reciprocal_rank`    DOUBLE        NULL,
    `min_score`               DOUBLE        NULL,
    `topk`                    INT           NULL,
    `ndcg`                    DOUBLE        NULL,
    `citation_hit_rate`       DOUBLE        NULL,
    `citation_coverage`       DOUBLE        NULL,
    `created_at`              DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_eval_run_id` (`run_id`),
    KEY `idx_rag_eval_created` (`created_at`),
    KEY `idx_rag_eval_hit_rate_created` (`hit_rate`, `created_at`),
    KEY `idx_rag_evaluation_runs_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eval_runs` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`                 BIGINT        NOT NULL DEFAULT 1,
    `run_id`                  VARCHAR(80)   NOT NULL,
    `title`                   VARCHAR(120)  NOT NULL,
    `baseline_key`            VARCHAR(80)   NOT NULL DEFAULT 'default',
    `baseline`                TINYINT(1)    NOT NULL DEFAULT 0,
    `request_json`            LONGTEXT      NULL,
    `response_json`           LONGTEXT      NULL,
    `intent_total`            INT           NULL,
    `intent_correct`          INT           NULL,
    `intent_accuracy`         DOUBLE        NULL,
    `intent_macro_f1`         DOUBLE        NULL,
    `rag_run_id`              VARCHAR(80)   NULL,
    `rag_hit_rate`            DOUBLE        NULL,
    `rag_mrr`                 DOUBLE        NULL,
    `rag_ndcg`                DOUBLE        NULL,
    `judge_total`             INT           NULL,
    `judge_passed`            INT           NULL,
    `judge_pass_rate`         DOUBLE        NULL,
    `judge_average_overall`   DOUBLE        NULL,
    `judge_average_relevance` DOUBLE        NULL,
    `judge_average_accuracy`  DOUBLE        NULL,
    `judge_average_completeness` DOUBLE      NULL,
    `judge_average_helpfulness` DOUBLE       NULL,
    `overall_score`           DOUBLE        NULL,
    `regression`              TINYINT(1)    NOT NULL DEFAULT 0,
    `regression_threshold`    DOUBLE        NULL,
    `created_at`              DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_eval_runs_run_id` (`run_id`),
    KEY `idx_eval_runs_user_created` (`user_id`, `created_at` DESC),
    KEY `idx_eval_runs_baseline` (`user_id`, `baseline_key`, `baseline`, `created_at` DESC),
    KEY `idx_eval_runs_regression` (`regression`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_query_traces` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`                 BIGINT       NOT NULL,
    `trace_id`                VARCHAR(80)  NOT NULL,
    `question`                TEXT         NOT NULL,
    `rewritten_question`      TEXT         NULL,
    `route_strategy`          VARCHAR(40)  NULL,
    `route_reasoning`         VARCHAR(500) NULL,
    `decomposed_queries_json` TEXT         NULL,
    `crag_grade`              VARCHAR(20)  NULL,
    `crag_action`             VARCHAR(200) NULL,
    `graph_attempted`         TINYINT      NULL,
    `graph_hit`               TINYINT      NULL,
    `graph_result`            TEXT         NULL,
    `knowledge_base_ids_json` TEXT         NULL,
    `retrieved_json`          TEXT         NULL,
    `reranked_json`           TEXT         NULL,
    `final_sources_json`      TEXT         NULL,
    `answer`                  TEXT         NULL,
    `confidence`              DOUBLE       NULL,
    `invalid_citations_json`  TEXT         NULL,
    `latency_ms`              BIGINT       NULL,
    `created_at`              DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_trace_id` (`trace_id`),
    KEY `idx_rag_query_traces_user_created` (`user_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 文字面试 ====================
CREATE TABLE IF NOT EXISTS `interview_sessions` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`                  BIGINT       NOT NULL DEFAULT 1,
    `session_id`               VARCHAR(36)  NOT NULL,
    `skill_id`                 VARCHAR(64)  NULL,
    `difficulty`               VARCHAR(16)  NULL,
    `resume_id`                BIGINT       NULL,
    `total_questions`          INT          NULL,
    `current_question_index`   INT          NULL DEFAULT 0,
    `status`                   VARCHAR(20)  NULL,
    `questions_json`           TEXT         NULL,
    `overall_score`            INT          NULL,
    `overall_feedback`         TEXT         NULL,
    `strengths_json`           TEXT         NULL,
    `improvements_json`        TEXT         NULL,
    `reference_answers_json`   TEXT         NULL,
    `created_at`               DATETIME(6)  NOT NULL,
    `completed_at`             DATETIME(6)  NULL,
    `evaluate_status`          VARCHAR(20)  NULL,
    `evaluate_error`           VARCHAR(500) NULL,
    `llm_provider`             VARCHAR(50)  NULL,
    `knowledge_base_ids_json`  TEXT         NULL,
    `interview_plan_json`      TEXT         NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_session_id` (`session_id`),
    KEY `idx_interview_session_resume_created` (`resume_id`, `created_at`),
    KEY `idx_interview_session_resume_status_created` (`resume_id`, `status`, `created_at`),
    KEY `idx_interview_session_skill_created` (`skill_id`, `created_at`),
    KEY `idx_interview_sessions_user_id` (`user_id`),
    CONSTRAINT `fk_interview_sessions_resume` FOREIGN KEY (`resume_id`) REFERENCES `resumes` (`id`),
    CONSTRAINT `fk_interview_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `interview_answers` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT      NOT NULL DEFAULT 1,
    `session_id`        BIGINT      NOT NULL,
    `question_index`    INT         NULL,
    `question`          TEXT        NULL,
    `category`          VARCHAR(255) NULL,
    `user_answer`       TEXT        NULL,
    `score`             INT         NULL,
    `feedback`          TEXT        NULL,
    `reference_answer`  TEXT        NULL,
    `key_points_json`   TEXT        NULL,
    `answered_at`       DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_answer_session_question` (`session_id`, `question_index`),
    KEY `idx_interview_answer_session_question` (`session_id`, `question_index`),
    KEY `idx_interview_answers_user_id` (`user_id`),
    CONSTRAINT `fk_interview_answers_session` FOREIGN KEY (`session_id`) REFERENCES `interview_sessions` (`id`),
    CONSTRAINT `fk_interview_answers_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Multi-Agent 编排轨迹（Planner/Interviewer/Critic/Evaluator 决策步骤，按会话回放）
CREATE TABLE IF NOT EXISTS `agent_run_steps` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT        NOT NULL DEFAULT 1,
    `session_id`      VARCHAR(36)   NOT NULL,
    `question_index`  INT           NULL,
    `role`            VARCHAR(20)   NOT NULL,
    `step_order`      INT           NOT NULL DEFAULT 0,
    `action`          VARCHAR(64)   NOT NULL,
    `action_input`    TEXT          NULL,
    `observation`     TEXT          NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_agent_run_steps_session` (`session_id`, `id`),
    KEY `idx_agent_run_steps_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 跨会话候选人画像记忆（评估完成后 LLM 抽取的 strength/weakness 条目）
CREATE TABLE IF NOT EXISTS `candidate_memory` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT        NOT NULL,
    `skill_id`    VARCHAR(64)   NULL,
    `topic`       VARCHAR(128)  NOT NULL,
    `kind`        VARCHAR(16)   NOT NULL,
    `evidence`    VARCHAR(500)  NULL,
    `session_id`  VARCHAR(36)   NULL,
    `created_at`  DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_candidate_memory_user_skill` (`user_id`, `skill_id`, `created_at` DESC),
    KEY `idx_candidate_memory_user_topic` (`user_id`, `topic`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `interview_schedule` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NOT NULL DEFAULT 1,
    `company_name`    VARCHAR(255) NOT NULL,
    `position`        VARCHAR(255) NOT NULL,
    `interview_time`  DATETIME(6)  NOT NULL,
    `interview_type`  VARCHAR(255) NULL,
    `interviewer`     VARCHAR(255) NULL,
    `round_number`    INT          NULL,
    `status`          VARCHAR(255) NOT NULL,
    `meeting_link`    TEXT         NULL,
    `notes`           TEXT         NULL,
    `created_at`      DATETIME(6)  NULL,
    `updated_at`      DATETIME(6)  NULL,
    PRIMARY KEY (`id`),
    KEY `idx_interview_schedule_user_id` (`user_id`),
    KEY `idx_interview_schedule_time_status` (`interview_time`, `status`),
    CONSTRAINT `fk_interview_schedule_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 语音面试 ====================
CREATE TABLE IF NOT EXISTS `voice_interview_sessions` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT       NOT NULL DEFAULT 1,
    `role_type`         VARCHAR(255) NOT NULL,
    `skill_id`          VARCHAR(64)  NULL,
    `difficulty`        VARCHAR(16)  NULL,
    `resume_id`         BIGINT       NULL,
    `custom_jd_text`    TEXT         NULL,
    `status`            VARCHAR(255) NULL,
    `current_phase`     VARCHAR(255) NULL,
    `intro_enabled`     TINYINT(1)   NULL,
    `tech_enabled`      TINYINT(1)   NULL,
    `project_enabled`   TINYINT(1)   NULL,
    `hr_enabled`        TINYINT(1)   NULL,
    `planned_duration`  INT          NULL,
    `actual_duration`   INT          NULL,
    `start_time`        DATETIME(6)  NULL,
    `end_time`          DATETIME(6)  NULL,
    `paused_at`         DATETIME(6)  NULL,
    `resumed_at`        DATETIME(6)  NULL,
    `evaluate_status`   VARCHAR(255) NULL,
    `evaluate_error`    VARCHAR(500) NULL,
    `llm_provider`      VARCHAR(50)  NULL,
    `created_at`        DATETIME(6)  NULL,
    `updated_at`        DATETIME(6)  NULL,
    PRIMARY KEY (`id`),
    KEY `idx_voice_interview_sessions_user_id` (`user_id`),
    CONSTRAINT `fk_voice_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `voice_interview_messages` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`            BIGINT       NULL,
    `message_type`          VARCHAR(255) NOT NULL,
    `phase`                 VARCHAR(255) NULL,
    `sequence_num`          INT          NULL,
    `user_recognized_text`  TEXT         NULL,
    `ai_generated_text`     TEXT         NULL,
    `timestamp`             DATETIME(6)  NULL,
    `created_at`            DATETIME(6)  NULL,
    PRIMARY KEY (`id`),
    KEY `idx_voice_messages_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `voice_interview_evaluations` (
    `id`                          BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`                  BIGINT      NULL,
    `interviewer_role`            VARCHAR(255) NULL,
    `interview_date`              DATETIME(6) NULL,
    `overall_score`               INT         NULL,
    `overall_feedback`            TEXT        NULL,
    `strengths_json`              TEXT        NULL,
    `improvements_json`           TEXT        NULL,
    `question_evaluations_json`   TEXT        NULL,
    `reference_answers_json`      TEXT        NULL,
    `created_at`                  DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_voice_eval_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== LLM Provider ====================
CREATE TABLE IF NOT EXISTS `llm_provider_config` (
    `id`                    VARCHAR(64)   NOT NULL,
    `base_url`              VARCHAR(512)  NOT NULL,
    `api_key_ciphertext`    VARCHAR(4096) NOT NULL,
    `api_key_nonce`         VARCHAR(64)   NOT NULL,
    `model`                 VARCHAR(128)  NOT NULL,
    `embedding_model`       VARCHAR(128)  NULL,
    `embedding_dimensions`  INT           NULL,
    `supports_embedding`    TINYINT(1)    NOT NULL,
    `temperature`           DOUBLE        NULL,
    `enabled`               TINYINT(1)    NOT NULL,
    `builtin`               TINYINT(1)    NOT NULL,
    `created_at`            DATETIME(6)   NOT NULL,
    `updated_at`            DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `llm_global_setting` (
    `id`                           BIGINT      NOT NULL AUTO_INCREMENT,
    `default_chat_provider_id`     VARCHAR(64) NOT NULL,
    `default_embedding_provider_id` VARCHAR(64) NOT NULL,
    `created_at`                   DATETIME(6) NOT NULL,
    `updated_at`                   DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 用户级 LLM Provider（BYOK：每用户一条「我的模型」，仅 Chat 走 per-user，Embedding 仍全局）
CREATE TABLE IF NOT EXISTS `user_llm_provider` (
    `user_id`               BIGINT        NOT NULL,
    `base_url`              VARCHAR(512)  NOT NULL,
    `api_key_ciphertext`    VARCHAR(1024) NOT NULL,
    `api_key_nonce`         VARCHAR(128)  NOT NULL,
    `chat_model`            VARCHAR(128)  NOT NULL,
    `temperature`           DOUBLE        NULL,
    `created_at`            DATETIME(6)   NULL,
    `updated_at`            DATETIME(6)   NULL,
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
