-- AI Interview Platform V2 fresh MySQL schema (initialized as one atomic contract)
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

CREATE TABLE IF NOT EXISTS `roles` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(50)  NOT NULL,
    `name`        VARCHAR(100) NOT NULL,
    `description` VARCHAR(255) NULL,
    `created_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_roles_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_roles` (
    `user_id`     BIGINT      NOT NULL,
    `role_id`     BIGINT      NOT NULL,
    `created_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`user_id`, `role_id`),
    CONSTRAINT `fk_user_roles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_roles_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `roles` (`code`, `name`, `description`) VALUES
    ('ADMIN', '管理员', '系统管理员'),
    ('USER', '用户', '普通用户')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

INSERT INTO `users` (`id`, `username`, `email`, `password_hash`, `display_name`, `role`, `enabled`, `created_at`)
VALUES (1, 'admin', 'admin@ai-interview.local',
        '$2a$10$ITjz94ki.PdJE90jdYJMrOkwaopW3yLJy73ZbFUkJvGg4.kLjosC.',
        'Default Admin', 'ADMIN', 1, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE `id` = `id`;

INSERT IGNORE INTO `user_roles` (`user_id`, `role_id`)
SELECT 1, `id` FROM `roles` WHERE `code` = 'ADMIN';

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
CREATE TABLE IF NOT EXISTS `documents` (
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
    `accessible_by`        VARCHAR(32)  NULL DEFAULT 'PRIVATE',
    `expire_date`          DATE         NULL,
    `knowledge_base_type`  VARCHAR(32)  NULL DEFAULT 'DOCUMENT_SEARCH',
    `table_name`           VARCHAR(128) NULL,
    `lock_version`         INT          NOT NULL DEFAULT 0,
    `deleted`              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_kb_user_hash` (`user_id`, `file_hash`),
    KEY `idx_kb_category` (`category`),
    KEY `idx_documents_user_id` (`user_id`),
    CONSTRAINT `fk_documents_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_permissions` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `document_id`     BIGINT       NOT NULL,
    `version_id`      BIGINT       NULL,
    `principal_type`  VARCHAR(32)  NOT NULL,
    `principal_id`    VARCHAR(128) NOT NULL,
    `permission`      VARCHAR(32)  NOT NULL DEFAULT 'READ',
    `expires_at`      DATETIME(6)  NULL,
    `created_at`      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`      DATETIME(6)  NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_permission` (`document_id`, `version_id`, `principal_type`, `principal_id`, `permission`),
    KEY `idx_document_permission_principal` (`principal_type`, `principal_id`, `permission`),
    CONSTRAINT `fk_document_permission_document` FOREIGN KEY (`document_id`) REFERENCES `documents` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_versions` (
    `version_id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `doc_id`             BIGINT       NOT NULL,
    `version`            VARCHAR(32)  NOT NULL,
    `doc_url`            VARCHAR(1000) NULL,
    `storage_key`        VARCHAR(500) NULL,
    `converted_doc_url`  VARCHAR(1000) NULL COMMENT '转换后 Markdown 的 MinIO URL',
    `converted_content`  LONGTEXT     NULL COMMENT '历史兼容：早期全文 Markdown；新上传不再写入',
    `content_hash`       VARCHAR(64)  NULL,
    `status`             VARCHAR(20)  NULL,
    `embedding_attempt`  INT          NOT NULL DEFAULT 0,
    `embedding_claimed_at` DATETIME(6) NULL,
    `embedding_next_retry_at` DATETIME(6) NULL,
    `embedding_last_error` VARCHAR(1000) NULL,
    `embedding_terminal_failure` TINYINT(1) NOT NULL DEFAULT 0,
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
    KEY `idx_kbv_embedding_recovery`
        (`status`, `embedding_terminal_failure`, `embedding_next_retry_at`, `embedding_claimed_at`),
    CONSTRAINT `fk_kbv_doc` FOREIGN KEY (`doc_id`) REFERENCES `documents` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_segments` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`             BIGINT       NOT NULL,
    -- PARENT_CHILD 会保留完整父分段（skip_embedding=1），长章节可能超过 64KB。
    -- 使用 MEDIUMTEXT，避免中文 PDF 在切块落库时触发 TEXT 截断。
    `text`                MEDIUMTEXT   NOT NULL,
    `chunk_id`            VARCHAR(64)  NULL,
    `parent_chunk_id`     VARCHAR(64)  NULL,
    `brother_chunk_id`    VARCHAR(64)  NULL,
    `brother_chunk_index` INT          NULL,
    `metadata`            TEXT         NULL,
    `data_domain`         VARCHAR(16)  NOT NULL,
    `resource_id`         VARCHAR(191) NOT NULL,
    `resource_version`    VARCHAR(191) NOT NULL,
    `evidence_id`         VARCHAR(191) NOT NULL,
    `content_hash`        CHAR(64)     NOT NULL,
    `source_type`         VARCHAR(64)  NOT NULL,
    `source_locator`      VARCHAR(1000) NOT NULL,
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
    UNIQUE KEY `uk_kbs_evidence_id` (`evidence_id`),
    KEY `idx_kbs_evidence_scope` (`user_id`, `data_domain`, `resource_id`, `resource_version`),
    KEY `idx_kbs_doc_version` (`document_id`, `document_version`),
    KEY `idx_kbs_status` (`status`),
    KEY `idx_kbs_chunk_id` (`chunk_id`),
    KEY `idx_kbs_brother` (`brother_chunk_id`),
    CONSTRAINT `fk_kbs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 统一聊天与长期记忆 ====================
CREATE TABLE IF NOT EXISTS `chat_sessions` (
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
    KEY `idx_chat_sessions_user_id` (`user_id`),
    CONSTRAINT `fk_chat_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chat_messages` (
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
    KEY `idx_chat_message_session` (`session_id`),
    KEY `idx_chat_message_order` (`session_id`, `message_order`),
    CONSTRAINT `fk_chat_messages_session` FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_session_knowledge_bases` (
    `session_id`         BIGINT NOT NULL,
    `knowledge_base_id`  BIGINT NOT NULL,
    PRIMARY KEY (`session_id`, `knowledge_base_id`),
    KEY `idx_rskb_kb_id` (`knowledge_base_id`),
    CONSTRAINT `fk_rskb_session` FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`id`),
    CONSTRAINT `fk_rskb_kb` FOREIGN KEY (`knowledge_base_id`) REFERENCES `documents` (`id`)
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
    `retrieval_recall`        DOUBLE        NULL,
    `retrieval_precision`     DOUBLE        NULL,
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

-- 评测数据集与逐案例结果；eval_runs 仅保留运行摘要和模型原始快照。
CREATE TABLE IF NOT EXISTS `eval_datasets` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `dataset_key`    VARCHAR(100) NOT NULL,
    `name`           VARCHAR(160) NOT NULL,
    `domain`         VARCHAR(64)  NULL,
    `version`        VARCHAR(32)  NOT NULL DEFAULT '1.0.0',
    `description`    VARCHAR(500) NULL,
    `owner_user_id`  BIGINT       NULL,
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`     DATETIME(6)  NOT NULL,
    `updated_at`     DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_eval_datasets_key_version` (`dataset_key`, `version`),
    KEY `idx_eval_datasets_owner` (`owner_user_id`),
    CONSTRAINT `fk_eval_datasets_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eval_cases` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `dataset_id`             BIGINT        NOT NULL,
    `case_key`               VARCHAR(100)  NOT NULL,
    `case_type`              VARCHAR(32)   NOT NULL,
    `question`               TEXT          NOT NULL,
    `expected_intent`        VARCHAR(80)   NULL,
    `expected_route`         VARCHAR(40)   NULL,
    `expected_evidence_json` TEXT          NULL,
    `expected_answer`        TEXT          NULL,
    `conversation_json`      TEXT          NULL,
    `metadata_json`          TEXT          NULL,
    `created_at`             DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_eval_cases_dataset_key` (`dataset_id`, `case_key`),
    KEY `idx_eval_cases_type` (`dataset_id`, `case_type`),
    CONSTRAINT `fk_eval_cases_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `eval_datasets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eval_results` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `eval_run_id`        BIGINT        NOT NULL,
    `case_id`            BIGINT        NULL,
    `actual_intent`      VARCHAR(80)   NULL,
    `actual_route`       VARCHAR(40)   NULL,
    `recall_at_k`        DOUBLE        NULL,
    `precision_at_k`     DOUBLE        NULL,
    `mrr`                DOUBLE        NULL,
    `ndcg`               DOUBLE        NULL,
    `citation_coverage`  DOUBLE        NULL,
    `groundedness`       DOUBLE        NULL,
    `relevance`          DOUBLE        NULL,
    `accuracy`           DOUBLE        NULL,
    `completeness`       DOUBLE        NULL,
    `helpfulness`        DOUBLE        NULL,
    `latency_ms`         BIGINT        NULL,
    `status`             VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    `failure_reason`     VARCHAR(500)  NULL,
    `details_json`       TEXT          NULL,
    `created_at`         DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_eval_results_run_case` (`eval_run_id`, `case_id`),
    KEY `idx_eval_results_run_status` (`eval_run_id`, `status`),
    CONSTRAINT `fk_eval_results_run` FOREIGN KEY (`eval_run_id`) REFERENCES `eval_runs` (`id`),
    CONSTRAINT `fk_eval_results_case` FOREIGN KEY (`case_id`) REFERENCES `eval_cases` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `eval_baselines` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `baseline_key`   VARCHAR(80)  NOT NULL,
    `eval_run_id`    BIGINT       NOT NULL,
    `metric_json`    TEXT         NOT NULL,
    `threshold`      DOUBLE       NOT NULL DEFAULT 0.05,
    `created_at`     DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_eval_baselines_key_run` (`baseline_key`, `eval_run_id`),
    KEY `idx_eval_baselines_key_created` (`baseline_key`, `created_at` DESC),
    CONSTRAINT `fk_eval_baselines_run` FOREIGN KEY (`eval_run_id`) REFERENCES `eval_runs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_query_traces` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`                 BIGINT       NOT NULL,
    `trace_id`                VARCHAR(80)  NOT NULL,
    `rag_run_id`              VARCHAR(80)  NULL,
    `question`                TEXT         NOT NULL,
    `rewritten_question`      TEXT         NULL,
    `decomposed_queries_json` TEXT         NULL,
    `crag_grade`              VARCHAR(20)  NULL,
    `crag_action`             VARCHAR(200) NULL,
    `route_source`            VARCHAR(32)  NULL,
    `route_intent`            VARCHAR(120) NULL,
    `route_confidence`        DOUBLE       NULL,
    `route_reasoning`         VARCHAR(500) NULL,
    `knowledge_base_ids_json` TEXT         NULL,
    `evidence_scope_json`     TEXT         NULL,
    `evidence_status`         VARCHAR(20)  NULL,
    `evidence_refs_json`      TEXT         NULL,
    `degraded_reasons_json`   TEXT         NULL,
    `retrieved_json`          TEXT         NULL,
    `reranked_json`           TEXT         NULL,
    `final_sources_json`      TEXT         NULL,
    `answer`                  TEXT         NULL,
    `confidence`              DOUBLE       NULL,
    `invalid_citations_json`  TEXT         NULL,
    `latency_ms`              BIGINT       NULL,
    `created_at`              DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rag_query_traces_trace_created` (`trace_id`, `created_at` DESC),
    KEY `idx_rag_query_traces_rag_run` (`rag_run_id`),
    KEY `idx_rag_query_traces_user_created` (`user_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- DATA_QUERY 动态表元数据（增加 user_id 做多租户隔离）
CREATE TABLE IF NOT EXISTS `table_meta` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT       NOT NULL,
    `table_name`   VARCHAR(128) NOT NULL,
    `description`  VARCHAR(512) NULL,
    `create_sql`   TEXT         NULL,
    `columns_info` TEXT         NULL,
    `version_id`   BIGINT       NULL,
    `created_at`   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
    `lock_version` INT          NOT NULL DEFAULT 0,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_meta_name` (`table_name`),
    KEY `idx_table_meta_user_id` (`user_id`),
    KEY `idx_table_meta_version_id` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Text2SQL Schema 目录：只暴露启用且在白名单内的业务表结构给模型。
CREATE TABLE IF NOT EXISTS `rag_table_meta` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `table_name`   VARCHAR(64)  NOT NULL,
    `schema_ddl`   TEXT         NOT NULL,
    `description`  VARCHAR(500) NULL,
    `enabled`      TINYINT(1)   NOT NULL DEFAULT 1,
    `version`      VARCHAR(32)  NOT NULL DEFAULT '1.0.0',
    `created_at`   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_table_meta_name` (`table_name`),
    KEY `idx_rag_table_meta_enabled` (`enabled`)
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
    `capability_template_code` VARCHAR(64)  NULL,
    `capability_template_version` VARCHAR(32) NULL,
    `plan_version`             VARCHAR(80)  NULL,
    `prompt_version`           VARCHAR(64)  NULL,
    `evidence_snapshot_id`     VARCHAR(80)  NULL,
    `evidence_snapshot_ids_json` TEXT       NULL,
    `github_repository_id`     BIGINT       NULL,
    `github_commit_sha`        CHAR(40)     NULL,
    `session_version`          BIGINT       NOT NULL DEFAULT 0,
    `current_stage`            VARCHAR(32)  NULL,
    `current_question_id`      BIGINT       NULL,
    `personal_knowledge_enabled` TINYINT(1) NOT NULL DEFAULT 0,
    `degraded_reasons_json`    TEXT         NULL,
    `active_command_id`        VARCHAR(64)  NULL,
    `continuation_count`       INT          NOT NULL DEFAULT 0,
    `reflection_count`         INT          NOT NULL DEFAULT 0,
    `started_at`               DATETIME(6)  NULL,
    `stage_started_at`         DATETIME(6)  NULL,
    `stage_deadline_at`        DATETIME(6)  NULL,
    `soft_deadline_at`         DATETIME(6)  NULL,
    `last_activity_at`         DATETIME(6)  NULL,
    `resume_expires_at`        DATETIME(6)  NULL,
    `paused_at`                DATETIME(6)  NULL,
    `aborted_at`               DATETIME(6)  NULL,
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
    `command_id`        VARCHAR(64) NULL,
    `question_id`       BIGINT      NULL,
    `assessment_status` VARCHAR(24) NULL,
    `assessment_json`   LONGTEXT    NULL,
    `assessment_confidence` DECIMAL(6,5) NULL,
    `recommended_action` VARCHAR(32) NULL,
    `evidence_status`   VARCHAR(20) NULL,
    `objective_evidence_ids_json` TEXT NULL,
    `prompt_version`    VARCHAR(64) NULL,
    `model_snapshot`    VARCHAR(191) NULL,
    `latency_ms`        BIGINT      NULL,
    `input_tokens`      INT         NULL,
    `output_tokens`     INT         NULL,
    `retry_count`       INT         NOT NULL DEFAULT 0,
    `degraded_reason`   VARCHAR(255) NULL,
    `answered_at`       DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_answer_session_question` (`session_id`, `question_index`),
    KEY `idx_interview_answer_session_question` (`session_id`, `question_index`),
    KEY `idx_interview_answers_user_id` (`user_id`),
    UNIQUE KEY `uk_interview_answer_command` (`session_id`, `command_id`),
    CONSTRAINT `fk_interview_answers_session` FOREIGN KEY (`session_id`) REFERENCES `interview_sessions` (`id`),
    CONSTRAINT `fk_interview_answers_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 冻结主问题和有限追问；sort_order 为主问题预留间隙，追问可插入而不重排历史题号。
CREATE TABLE IF NOT EXISTS `interview_questions` (
    `id`                        BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`                   BIGINT        NOT NULL,
    `session_id`                BIGINT        NOT NULL,
    `question_index`            INT           NOT NULL,
    `sort_order`                INT           NOT NULL,
    `stage`                     VARCHAR(32)   NOT NULL,
    `question_type`             VARCHAR(32)   NOT NULL,
    `question_text`             TEXT          NOT NULL,
    `capability_atom_id`        VARCHAR(64)   NULL,
    `capability_atom_version`   VARCHAR(64)   NULL,
    `question_template_code`    VARCHAR(64)   NULL,
    `question_template_version` VARCHAR(32)   NULL,
    `rubric_code`               VARCHAR(64)   NULL,
    `rubric_version`            VARCHAR(32)   NULL,
    `evidence_snapshot_id`      VARCHAR(80)   NULL,
    `evidence_ids_json`         TEXT          NULL,
    `budget_seconds`            INT           NOT NULL,
    `parent_question_id`        BIGINT        NULL,
    `follow_up`                 TINYINT(1)    NOT NULL DEFAULT 0,
    `reflection_rounds`         INT           NOT NULL DEFAULT 0,
    `prompt_version`            VARCHAR(64)   NOT NULL,
    `model_snapshot`            VARCHAR(191)  NULL,
    `status`                    VARCHAR(20)   NOT NULL,
    `created_at`                DATETIME(6)   NOT NULL,
    `answered_at`               DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_question_index` (`session_id`, `question_index`),
    UNIQUE KEY `uk_interview_question_sort` (`session_id`, `sort_order`),
    KEY `idx_interview_question_user_session` (`user_id`, `session_id`, `sort_order`),
    CONSTRAINT `fk_interview_question_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_interview_question_session` FOREIGN KEY (`session_id`) REFERENCES `interview_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- REST 指令幂等事实；updated_at 同时作为短期执行租约时间戳，不保存逐 Token 事件。
CREATE TABLE IF NOT EXISTS `interview_commands` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`                  BIGINT        NOT NULL,
    `session_id`               VARCHAR(36)   NOT NULL,
    `command_id`               VARCHAR(64)   NOT NULL,
    `trace_id`                 VARCHAR(64)   NULL,
    `agent_run_id`             VARCHAR(64)   NULL,
    `command_type`             VARCHAR(32)   NOT NULL,
    `expected_session_version` BIGINT        NOT NULL,
    `status`                   VARCHAR(20)   NOT NULL,
    `result_json`              LONGTEXT      NULL,
    `failure_code`             VARCHAR(64)   NULL,
    `failure_detail`           VARCHAR(500)  NULL,
    `created_at`               DATETIME(6)   NOT NULL,
    `updated_at`               DATETIME(6)   NOT NULL,
    `completed_at`             DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_command` (`session_id`, `command_id`),
    KEY `idx_interview_command_user_session` (`user_id`, `session_id`, `created_at` DESC),
    KEY `idx_interview_command_trace` (`trace_id`, `created_at` DESC),
    CONSTRAINT `fk_interview_command_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- SSE 可重连事件摘要；完整会话状态仍从 interview_sessions / questions / answers 恢复。
CREATE TABLE IF NOT EXISTS `interview_session_events` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT        NOT NULL,
    `session_id`      VARCHAR(36)   NOT NULL,
    `event_type`      VARCHAR(40)   NOT NULL,
    `source_trace_id` VARCHAR(64)   NULL,
    `session_version` BIGINT        NOT NULL,
    `payload_json`    TEXT          NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_interview_event_reconnect` (`user_id`, `session_id`, `id`),
    KEY `idx_interview_event_trace` (`source_trace_id`, `created_at` DESC),
    CONSTRAINT `fk_interview_event_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Multi-Agent 编排轨迹（Planner/Interviewer/Critic/Evaluator 运行摘要）
CREATE TABLE IF NOT EXISTS `agent_runs` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`          VARCHAR(64)   NOT NULL,
    `trace_id`        VARCHAR(64)   NULL,
    `command_id`      VARCHAR(64)   NULL,
    `operation`       VARCHAR(64)   NOT NULL DEFAULT 'interview',
    `root_span_id`    VARCHAR(64)   NULL,
    `user_id`         BIGINT        NOT NULL DEFAULT 1,
    `session_id`      VARCHAR(36)   NOT NULL,
    `question_index`  INT           NULL,
    `status`          VARCHAR(32)   NOT NULL,
    `input_summary`   TEXT          NULL,
    `output_summary`  TEXT          NULL,
    `latency_ms`      BIGINT        NULL,
    `degraded_reason` VARCHAR(255)  NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    `completed_at`    DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_runs_run_id` (`run_id`),
    UNIQUE KEY `uk_agent_runs_command_operation` (`session_id`, `command_id`, `operation`),
    KEY `idx_agent_runs_trace` (`trace_id`, `created_at`),
    KEY `idx_agent_runs_session` (`session_id`, `created_at`),
    KEY `idx_agent_runs_user` (`user_id`),
    CONSTRAINT `fk_agent_runs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Multi-Agent 编排轨迹步骤（按运行回放）
CREATE TABLE IF NOT EXISTS `agent_steps` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`          VARCHAR(64)   NULL,
    `trace_id`        VARCHAR(64)   NULL,
    `span_id`         VARCHAR(64)   NULL,
    `parent_span_id`  VARCHAR(64)   NULL,
    `user_id`         BIGINT        NOT NULL DEFAULT 1,
    `session_id`      VARCHAR(36)   NOT NULL,
    `question_index`  INT           NULL,
    `role`            VARCHAR(20)   NOT NULL,
    `step_order`      INT           NOT NULL DEFAULT 0,
    `action`          VARCHAR(64)   NOT NULL,
    `action_input`    TEXT          NULL,
    `observation`     TEXT          NULL,
    `status`          VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    `latency_ms`      BIGINT        NULL,
    `metadata_json`   TEXT          NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_agent_steps_run` (`run_id`, `id`),
    KEY `idx_agent_steps_trace` (`trace_id`, `created_at`),
    KEY `idx_agent_steps_session` (`session_id`, `id`),
    KEY `idx_agent_steps_user` (`user_id`),
    CONSTRAINT `fk_agent_steps_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 工具执行审计；只保存脱敏摘要和执行状态，不保存完整输入输出。
CREATE TABLE IF NOT EXISTS `agent_tool_runs` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `tool_run_id`       VARCHAR(80)   NOT NULL,
    `agent_run_id`      VARCHAR(64)   NULL,
    `rag_run_id`        VARCHAR(80)   NULL,
    `trace_id`          VARCHAR(64)   NOT NULL,
    `session_id`        VARCHAR(36)   NULL,
    `user_id`           BIGINT        NOT NULL,
    `span_id`           VARCHAR(64)   NULL,
    `parent_span_id`    VARCHAR(64)   NULL,
    `tool_name`         VARCHAR(64)   NOT NULL,
    `status`            VARCHAR(20)   NOT NULL,
    `cache_hit`         TINYINT(1)    NOT NULL DEFAULT 0,
    `retry_count`       INT           NOT NULL DEFAULT 0,
    `input_summary`     VARCHAR(1000) NULL,
    `output_summary`    VARCHAR(1000) NULL,
    `fallback_reason`   VARCHAR(255)  NULL,
    `error_code`        VARCHAR(64)   NULL,
    `latency_ms`        BIGINT        NULL,
    `started_at`        DATETIME(6)   NOT NULL,
    `completed_at`      DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_tool_run_id` (`tool_run_id`),
    KEY `idx_agent_tool_trace_time` (`trace_id`, `started_at` DESC),
    KEY `idx_agent_tool_agent_time` (`agent_run_id`, `started_at` DESC),
    KEY `idx_agent_tool_rag_time` (`rag_run_id`, `started_at` DESC),
    KEY `idx_agent_tool_user_time` (`user_id`, `started_at` DESC),
    CONSTRAINT `fk_agent_tool_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 跨会话能力观测（逐题评估沉淀，按能力原子聚合为画像）
CREATE TABLE IF NOT EXISTS `chat_memories` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT        NOT NULL,
    `skill_id`           VARCHAR(64)   NULL,
    `capability_atom_id` VARCHAR(191)  NULL,
    `topic`              VARCHAR(128)  NOT NULL,
    `kind`               VARCHAR(16)   NOT NULL,
    `question_index`     INT           NULL,
    `mastery_score`      INT           NULL,
    `evidence`           VARCHAR(500)  NULL,
    `evidence_ids_json`  TEXT          NULL,
    `session_id`         VARCHAR(36)   NULL,
    `created_at`         DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_chat_memories_user_skill` (`user_id`, `skill_id`, `created_at` DESC),
    KEY `idx_chat_memories_user_topic` (`user_id`, `topic`),
    KEY `idx_chat_memories_user_atom` (`user_id`, `capability_atom_id`, `created_at` DESC),
    UNIQUE KEY `uk_chat_memories_session_question` (`session_id`, `question_index`)
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

-- ==================== 版本化能力目录与目标岗位 ====================
-- 内容由仓库内版本化 JSON 导入；已发布 code+version 不允许原地覆盖。
CREATE TABLE IF NOT EXISTS `capability_content_imports` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `schema_version`   VARCHAR(32)   NOT NULL,
    `content_version`  VARCHAR(64)   NOT NULL,
    `source_name`      VARCHAR(128)  NOT NULL,
    `source_locator`   VARCHAR(1000) NOT NULL,
    `checksum`         VARCHAR(80)   NOT NULL,
    `status`           VARCHAR(20)   NOT NULL,
    `imported_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_import_content_version` (`content_version`),
    UNIQUE KEY `uk_capability_import_checksum` (`checksum`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `capability_atom_definitions` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `atom_id`            VARCHAR(64)  NOT NULL,
    `version`            VARCHAR(32)  NOT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(1000) NOT NULL,
    `capability_domain`  VARCHAR(64)  NOT NULL,
    `job_tracks_json`    VARCHAR(500) NOT NULL,
    `parent_atom_id`     VARCHAR(64)  NULL,
    `content_hash`       CHAR(64)     NOT NULL,
    `created_at`         DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_atom_version` (`atom_id`, `version`),
    KEY `idx_capability_atom_domain` (`capability_domain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `capability_templates` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `template_code`    VARCHAR(64)   NOT NULL,
    `job_track`        VARCHAR(32)   NOT NULL,
    `version`          VARCHAR(32)   NOT NULL,
    `status`           VARCHAR(20)   NOT NULL,
    `source_name`      VARCHAR(128)  NOT NULL,
    `source_locator`   VARCHAR(1000) NOT NULL,
    `content_hash`     CHAR(64)      NOT NULL,
    `effective_date`   DATE          NOT NULL,
    `created_at`       DATETIME(6)   NOT NULL,
    `updated_at`       DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_template_version` (`template_code`, `version`),
    KEY `idx_capability_template_track_status` (`job_track`, `status`, `effective_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `template_capabilities` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `template_id`         BIGINT       NOT NULL,
    `atom_definition_id`  BIGINT       NOT NULL,
    `default_weight`      DECIMAL(8,6) NOT NULL,
    `minimum_coverage`    INT          NOT NULL DEFAULT 0,
    `question_types_json` VARCHAR(1000) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_capability_atom` (`template_id`, `atom_definition_id`),
    CONSTRAINT `fk_template_capability_template`
        FOREIGN KEY (`template_id`) REFERENCES `capability_templates` (`id`),
    CONSTRAINT `fk_template_capability_atom`
        FOREIGN KEY (`atom_definition_id`) REFERENCES `capability_atom_definitions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `evaluation_rubrics` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `rubric_code`      VARCHAR(64) NOT NULL,
    `version`          VARCHAR(32) NOT NULL,
    `status`           VARCHAR(20) NOT NULL,
    `dimensions_json`  TEXT        NOT NULL,
    `content_hash`     CHAR(64)    NOT NULL,
    `created_at`       DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_evaluation_rubric_version` (`rubric_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `question_templates` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `question_code`       VARCHAR(64)  NOT NULL,
    `version`             VARCHAR(32)  NOT NULL,
    `status`              VARCHAR(20)  NOT NULL,
    `atom_definition_id`  BIGINT       NOT NULL,
    `difficulty`          VARCHAR(20)  NOT NULL,
    `stage`               VARCHAR(32)  NOT NULL,
    `prompt_skeleton`     TEXT         NOT NULL,
    `rubric_code`         VARCHAR(64)  NOT NULL,
    `rubric_version`      VARCHAR(32)  NOT NULL,
    `content_hash`        CHAR(64)     NOT NULL,
    `created_at`          DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_template_version` (`question_code`, `version`),
    KEY `idx_question_template_atom_stage` (`atom_definition_id`, `stage`, `status`),
    CONSTRAINT `fk_question_template_atom`
        FOREIGN KEY (`atom_definition_id`) REFERENCES `capability_atom_definitions` (`id`),
    CONSTRAINT `fk_question_template_rubric`
        FOREIGN KEY (`rubric_code`, `rubric_version`)
        REFERENCES `evaluation_rubrics` (`rubric_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- PLATFORM 资料只保存审核摘要与来源清单；owner=0 是明确公共主体，不用 NULL 绕过权限。
CREATE TABLE IF NOT EXISTS `platform_knowledge_manifest` (
    `id`                        BIGINT        NOT NULL AUTO_INCREMENT,
    `owner_user_id`             BIGINT        NOT NULL DEFAULT 0,
    `data_domain`               VARCHAR(16)   NOT NULL DEFAULT 'PLATFORM',
    `evidence_id`               VARCHAR(191)  NOT NULL,
    `resource_id`               VARCHAR(191)  NOT NULL,
    `resource_version`          VARCHAR(64)   NOT NULL,
    `title`                     VARCHAR(255)  NOT NULL,
    `summary`                   TEXT          NOT NULL,
    `source_type`               VARCHAR(64)   NOT NULL,
    `source_locator`            VARCHAR(1000) NOT NULL,
    `content_hash`              CHAR(64)      NOT NULL,
    `capability_atom_ids_json`  TEXT          NOT NULL,
    `status`                    VARCHAR(20)   NOT NULL,
    `created_at`                DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_knowledge_evidence` (`evidence_id`),
    KEY `idx_platform_knowledge_scope`
        (`owner_user_id`, `data_domain`, `resource_id`, `resource_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 文档精准解析与证据快照 ====================
CREATE TABLE IF NOT EXISTS `document_tasks` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT        NOT NULL,
    `document_id`       BIGINT        NOT NULL,
    `version_id`        BIGINT        NOT NULL,
    `provider`          VARCHAR(32)   NOT NULL,
    `provider_task_id`  VARCHAR(191)  NULL,
    `status`            VARCHAR(32)   NOT NULL,
    `attempt`           INT           NOT NULL DEFAULT 0,
    `next_poll_at`      DATETIME(6)   NULL,
    `failure_code`      VARCHAR(64)   NULL,
    `failure_detail`    VARCHAR(500)  NULL,
    `fallback_used`     TINYINT(1)    NOT NULL DEFAULT 0,
    `fallback_reason`   VARCHAR(64)   NULL,
    `storage_key`       VARCHAR(500)  NULL,
    `file_name`         VARCHAR(255)  NULL,
    `content_type`      VARCHAR(255)  NULL,
    `started_at`        DATETIME(6)   NOT NULL,
    `completed_at`      DATETIME(6)   NULL,
    `created_at`        DATETIME(6)   NOT NULL,
    `updated_at`        DATETIME(6)   NOT NULL,
    `lock_version`      INT           NOT NULL DEFAULT 0,
    `deleted`           TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_parse_task_user_version` (`user_id`, `document_id`, `version_id`, `created_at` DESC),
    KEY `idx_parse_task_recovery` (`status`, `updated_at`, `next_poll_at`),
    UNIQUE KEY `uk_parse_provider_task` (`provider`, `provider_task_id`),
    CONSTRAINT `fk_parse_task_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `evidence_snapshots` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`              BIGINT        NOT NULL,
    `snapshot_id`          VARCHAR(80)   NOT NULL,
    `context_type`         VARCHAR(32)   NOT NULL,
    `context_id`           VARCHAR(80)   NOT NULL,
    `capability_atom_key`  VARCHAR(128)  NULL,
    `query_text`           VARCHAR(1000) NOT NULL,
    `evidence_status`      VARCHAR(20)   NOT NULL,
    `packet_json`          LONGTEXT      NOT NULL,
    `source_available`     TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`           DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_evidence_snapshot_id` (`snapshot_id`),
    KEY `idx_evidence_snapshot_context` (`user_id`, `context_type`, `context_id`, `created_at` DESC),
    CONSTRAINT `fk_evidence_snapshot_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `evidence_snapshot_refs` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT       NOT NULL,
    `snapshot_id`       VARCHAR(80)  NOT NULL,
    `data_domain`       VARCHAR(16)  NOT NULL,
    `resource_id`       VARCHAR(191) NOT NULL,
    `resource_version`  VARCHAR(191) NOT NULL,
    `evidence_id`       VARCHAR(191) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_evidence_snapshot_ref` (`snapshot_id`, `evidence_id`),
    KEY `idx_evidence_snapshot_source` (`user_id`, `data_domain`, `resource_id`),
    CONSTRAINT `fk_evidence_snapshot_ref_snapshot`
        FOREIGN KEY (`snapshot_id`) REFERENCES `evidence_snapshots` (`snapshot_id`),
    CONSTRAINT `fk_evidence_snapshot_ref_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== GitHub 公共仓库固定 SHA 证据 ====================
CREATE TABLE IF NOT EXISTS `github_repository_bindings` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`               BIGINT        NOT NULL,
    `owner_name`            VARCHAR(100)  NOT NULL,
    `repository_name`       VARCHAR(100)  NOT NULL,
    `repository_url`        VARCHAR(300)  NOT NULL,
    `default_branch`        VARCHAR(200)  NOT NULL,
    `fixed_commit_sha`      CHAR(40)      NOT NULL,
    `source_size_kb`        BIGINT        NOT NULL DEFAULT 0,
    `sync_status`           VARCHAR(32)   NOT NULL,
    `sync_fingerprint`      CHAR(64)      NULL,
    `synced_file_count`     INT           NOT NULL DEFAULT 0,
    `synced_bytes`          BIGINT        NOT NULL DEFAULT 0,
    `sync_error`            VARCHAR(500)  NULL,
    `source_available`      TINYINT(1)    NOT NULL DEFAULT 1,
    `core_modules_json`     TEXT          NOT NULL,
    `responsibilities`      TEXT          NOT NULL,
    `key_decisions`         TEXT          NOT NULL,
    `problems_solved`       TEXT          NOT NULL,
    `created_at`            DATETIME(6)   NOT NULL,
    `updated_at`            DATETIME(6)   NOT NULL,
    `last_synced_at`        DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_github_binding_snapshot`
        (`user_id`, `owner_name`, `repository_name`, `fixed_commit_sha`),
    KEY `idx_github_binding_user_status` (`user_id`, `sync_status`, `updated_at` DESC),
    CONSTRAINT `fk_github_binding_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 清单先于同步展示。只有通过路径与正文安全检查的文件才会写 content_snapshot。
CREATE TABLE IF NOT EXISTS `github_repository_files` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT        NOT NULL,
    `repository_id`     BIGINT        NOT NULL,
    `commit_sha`        CHAR(40)      NOT NULL,
    `path`              VARCHAR(500)  NOT NULL,
    `blob_sha`          CHAR(40)      NOT NULL,
    `byte_size`         BIGINT        NOT NULL,
    `language`          VARCHAR(32)   NULL,
    `file_kind`         VARCHAR(32)   NOT NULL,
    `status`            VARCHAR(40)   NOT NULL,
    `status_reason`     VARCHAR(255)  NULL,
    `default_included`  TINYINT(1)    NOT NULL DEFAULT 0,
    `content_hash`      CHAR(64)      NULL,
    `content_snapshot`  MEDIUMTEXT    NULL,
    `created_at`        DATETIME(6)   NOT NULL,
    `updated_at`        DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_github_file_snapshot` (`repository_id`, `commit_sha`, `path`),
    KEY `idx_github_file_user_repo_status` (`user_id`, `repository_id`, `status`),
    CONSTRAINT `fk_github_file_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_github_file_repository`
        FOREIGN KEY (`repository_id`) REFERENCES `github_repository_bindings` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `github_code_evidence` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `owner_user_id`   BIGINT        NOT NULL,
    `data_domain`     VARCHAR(16)   NOT NULL DEFAULT 'GITHUB',
    `resource_id`     VARCHAR(191)  NOT NULL,
    `resource_version` VARCHAR(64)  NOT NULL,
    `repository_id`   BIGINT        NOT NULL,
    `commit_sha`      CHAR(40)      NOT NULL,
    `path`            VARCHAR(500)  NOT NULL,
    `language`        VARCHAR(32)   NOT NULL,
    `symbol_name`     VARCHAR(255)  NOT NULL,
    `symbol_kind`     VARCHAR(32)   NOT NULL,
    `start_line`      INT           NOT NULL,
    `end_line`        INT           NOT NULL,
    `parent_summary`  VARCHAR(1000) NOT NULL,
    `content`         MEDIUMTEXT    NOT NULL,
    `content_hash`    CHAR(64)      NOT NULL,
    `evidence_id`     VARCHAR(80)   NOT NULL,
    `source_locator`  VARCHAR(1200) NOT NULL,
    `embedding_id`    VARCHAR(191)  NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_github_evidence_id` (`evidence_id`),
    UNIQUE KEY `uk_github_evidence_location`
        (`repository_id`, `commit_sha`, `path`(191), `start_line`, `end_line`, `content_hash`),
    KEY `idx_github_evidence_scope`
        (`owner_user_id`, `data_domain`, `resource_id`, `resource_version`),
    KEY `idx_github_evidence_location`
        (`repository_id`, `commit_sha`, `path`(191), `start_line`),
    CONSTRAINT `fk_github_evidence_user`
        FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_github_evidence_repository`
        FOREIGN KEY (`repository_id`) REFERENCES `github_repository_bindings` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 证据化复盘与能力画像 ====================
-- 客观事实先落库并立即可见；LLM 总结异步完成，只有 COMPLETED 报告可以写画像证据。
CREATE TABLE IF NOT EXISTS `interview_evidence_reports` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `report_id`            CHAR(36)      NOT NULL,
    `user_id`              BIGINT        NOT NULL,
    `session_id`           BIGINT        NOT NULL,
    `status`               VARCHAR(20)   NOT NULL,
    `objective_facts_json` LONGTEXT      NOT NULL,
    `summary_json`         LONGTEXT      NULL,
    `gaps_json`            LONGTEXT      NULL,
    `objective_ready`      TINYINT(1)    NOT NULL DEFAULT 1,
    `summary_ready`        TINYINT(1)    NOT NULL DEFAULT 0,
    `profile_applied`      TINYINT(1)    NOT NULL DEFAULT 0,
    `generation_attempt`   INT           NOT NULL DEFAULT 0,
    `generation_claimed_at` DATETIME(6)  NULL,
    `failure_code`         VARCHAR(64)   NULL,
    `failure_detail`       VARCHAR(500)  NULL,
    `created_at`           DATETIME(6)   NOT NULL,
    `updated_at`           DATETIME(6)   NOT NULL,
    `completed_at`         DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_evidence_report_id` (`report_id`),
    UNIQUE KEY `uk_interview_evidence_report_session` (`session_id`),
    KEY `idx_interview_evidence_report_user_time` (`user_id`, `created_at` DESC),
    CONSTRAINT `fk_interview_evidence_report_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_interview_evidence_report_session`
        FOREIGN KEY (`session_id`) REFERENCES `interview_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每条记录都是不可覆盖的能力证据历史；提示、看答案和重做会显式降低晋级资格。
CREATE TABLE IF NOT EXISTS `capability_evidence_history` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `evidence_record_id`     CHAR(36)      NOT NULL,
    `user_id`                BIGINT        NOT NULL,
    `report_id`              CHAR(36)      NULL,
    `session_id`             BIGINT        NULL,
    `question_id`            BIGINT        NULL,
    `training_task_id`       CHAR(36)      NULL,
    `capability_atom_id`     VARCHAR(191)  NOT NULL,
    `source_type`            VARCHAR(24)   NOT NULL,
    `difficulty`             VARCHAR(24)   NULL,
    `technical_score`        INT           NULL,
    `completeness_score`     INT           NULL,
    `objective_passed`       TINYINT(1)    NULL,
    `confidence`             DECIMAL(6,5)  NOT NULL,
    `evidence_status`        VARCHAR(20)   NOT NULL,
    `evidence_refs_json`     TEXT          NULL,
    `observation`            VARCHAR(500)  NULL,
    `eligible_for_promotion` TINYINT(1)    NOT NULL DEFAULT 0,
    `hint_used`              TINYINT(1)    NOT NULL DEFAULT 0,
    `answer_viewed`          TINYINT(1)    NOT NULL DEFAULT 0,
    `redo_count`             INT           NOT NULL DEFAULT 0,
    `occurred_at`            DATETIME(6)   NOT NULL,
    `created_at`             DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_evidence_record` (`evidence_record_id`),
    UNIQUE KEY `uk_capability_evidence_report_question`
        (`report_id`, `question_id`),
    UNIQUE KEY `uk_capability_evidence_training` (`training_task_id`),
    KEY `idx_capability_evidence_recent`
        (`user_id`, `capability_atom_id`, `occurred_at` DESC),
    CONSTRAINT `fk_capability_evidence_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 画像是证据历史的可重建投影，不保存伪精确总分。
CREATE TABLE IF NOT EXISTS `capability_profiles` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`                  BIGINT        NOT NULL,
    `capability_atom_id`       VARCHAR(191)  NOT NULL,
    `state`                    VARCHAR(20)   NOT NULL,
    `review_required`          TINYINT(1)    NOT NULL DEFAULT 0,
    `evidence_count`           INT           NOT NULL DEFAULT 0,
    `recent_evidence_ids_json` TEXT          NOT NULL,
    `last_evidence_at`         DATETIME(6)   NULL,
    `created_at`               DATETIME(6)   NOT NULL,
    `updated_at`               DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_profile_user_atom` (`user_id`, `capability_atom_id`),
    KEY `idx_capability_profile_user_state` (`user_id`, `state`, `updated_at` DESC),
    CONSTRAINT `fk_capability_profile_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 只保存调用元数据，不保存 API Key、完整 Prompt、简历、回答或源码。
CREATE TABLE IF NOT EXISTS `llm_usage_records` (
    `id`               BIGINT         NOT NULL AUTO_INCREMENT,
    `usage_id`         CHAR(36)       NOT NULL,
    `user_id`          BIGINT         NOT NULL,
    `session_id`       VARCHAR(36)    NULL,
    `report_id`        CHAR(36)       NULL,
    `operation`        VARCHAR(64)    NOT NULL,
    `provider`         VARCHAR(32)    NOT NULL,
    `model`            VARCHAR(191)   NULL,
    `status`           VARCHAR(20)    NOT NULL,
    `latency_ms`       BIGINT         NOT NULL,
    `input_tokens`     INT            NULL,
    `output_tokens`    INT            NULL,
    `total_tokens`     INT            NULL,
    `estimated_cost`   DECIMAL(18,8)  NULL,
    `currency`         VARCHAR(8)     NULL,
    `retry_count`      INT            NOT NULL DEFAULT 0,
    `degraded_reason`  VARCHAR(255)   NULL,
    `trace_id`         VARCHAR(64)    NULL,
    `agent_run_id`     VARCHAR(64)    NULL,
    `rag_run_id`       VARCHAR(80)    NULL,
    `span_id`          VARCHAR(64)    NULL,
    `created_at`       DATETIME(6)    NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_usage_id` (`usage_id`),
    KEY `idx_llm_usage_user_time` (`user_id`, `created_at` DESC),
    KEY `idx_llm_usage_session` (`user_id`, `session_id`, `created_at` DESC),
    KEY `idx_llm_usage_trace` (`trace_id`, `created_at` DESC),
    CONSTRAINT `fk_llm_usage_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== RAG 阶段化 Trace V2 ====================
-- 宽表 rag_query_traces 仅作为历史摘要保留；新链路写入以下可查询的阶段表。
CREATE TABLE IF NOT EXISTS `rag_runs` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `rag_run_id`     VARCHAR(80)   NOT NULL,
    `trace_id`       VARCHAR(80)   NOT NULL,
    `agent_run_id`   VARCHAR(64)   NULL,
    `root_span_id`   VARCHAR(64)   NULL,
    `user_id`        BIGINT        NOT NULL,
    `session_id`     VARCHAR(80)   NULL,
    `question`       VARCHAR(4000) NOT NULL,
    `status`         VARCHAR(20)   NOT NULL,
    `route_source`   VARCHAR(64)   NULL,
    `route_intent`   VARCHAR(120)  NULL,
    `latency_ms`     BIGINT        NULL,
    `degraded_reason` VARCHAR(255)  NULL,
    `answer_summary` VARCHAR(4000) NULL,
    `created_at`     DATETIME(6)   NOT NULL,
    `completed_at`   DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_run_id` (`rag_run_id`),
    KEY `idx_rag_run_trace` (`trace_id`, `created_at` DESC),
    KEY `idx_rag_run_agent` (`agent_run_id`, `created_at` DESC),
    KEY `idx_rag_run_user_time` (`user_id`, `created_at` DESC),
    KEY `idx_rag_run_status` (`status`),
    CONSTRAINT `fk_rag_run_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_stage_runs` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `rag_run_id`     VARCHAR(80)   NOT NULL,
    `stage`          VARCHAR(32)   NOT NULL,
    `status`         VARCHAR(20)   NOT NULL,
    `data_source`    VARCHAR(64)   NULL,
    `input_summary`  VARCHAR(4000) NULL,
    `output_summary` VARCHAR(4000) NULL,
    `metadata_json`  TEXT          NULL,
    `provider`       VARCHAR(64)   NULL,
    `model_name`     VARCHAR(128)  NULL,
    `input_tokens`   INT           NULL,
    `output_tokens`  INT           NULL,
    `filter_json`    TEXT          NULL,
    `fallback_reason` VARCHAR(1000) NULL,
    `started_at`     DATETIME(6)   NOT NULL,
    `completed_at`   DATETIME(6)   NULL,
    `latency_ms`     BIGINT        NULL,
    `error_message`  VARCHAR(1000) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rag_stage_run_time` (`rag_run_id`, `started_at`),
    CONSTRAINT `fk_rag_stage_run` FOREIGN KEY (`rag_run_id`) REFERENCES `rag_runs` (`rag_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_retrieval_candidates` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `rag_run_id`     VARCHAR(80)   NOT NULL,
    `stage`          VARCHAR(32)   NOT NULL,
    `rank_no`        INT           NULL,
    `source_type`    VARCHAR(64)   NULL,
    `document_id`    VARCHAR(191)  NULL,
    `segment_id`     VARCHAR(191)  NULL,
    `evidence_id`    VARCHAR(191)  NULL,
    `score`          DOUBLE        NULL,
    `rerank_score`   DOUBLE        NULL,
    `snippet`        VARCHAR(1000) NULL,
    `metadata_json`  TEXT          NULL,
    `permission_allowed` TINYINT(1) NULL,
    `version_matched`    TINYINT(1) NULL,
    `filter_reason`      VARCHAR(1000) NULL,
    `created_at`     DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rag_candidate_run_stage` (`rag_run_id`, `stage`, `rank_no`),
    CONSTRAINT `fk_rag_candidate_run` FOREIGN KEY (`rag_run_id`) REFERENCES `rag_runs` (`rag_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_citations` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `rag_run_id`      VARCHAR(80)   NOT NULL,
    `citation_index`  INT           NOT NULL,
    `evidence_id`     VARCHAR(191)  NULL,
    `source_locator`  VARCHAR(1000) NULL,
    `cited`           TINYINT(1)    NOT NULL DEFAULT 0,
    `valid`           TINYINT(1)    NOT NULL DEFAULT 1,
    `confidence`      DOUBLE        NULL,
    `created_at`      DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rag_citation_run` (`rag_run_id`, `citation_index`),
    CONSTRAINT `fk_rag_citation_run` FOREIGN KEY (`rag_run_id`) REFERENCES `rag_runs` (`rag_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rag_answer_snapshots` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `rag_run_id`            VARCHAR(80)   NOT NULL,
    `answer`                VARCHAR(4000) NULL,
    `grounded_status`       VARCHAR(32)   NULL,
    `confidence`            DOUBLE        NULL,
    `invalid_citations_json` TEXT         NULL,
    `token_count`           INT           NULL,
    `created_at`            DATETIME(6)   NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_rag_answer_run_time` (`rag_run_id`, `created_at` DESC),
    CONSTRAINT `fk_rag_answer_run` FOREIGN KEY (`rag_run_id`) REFERENCES `rag_runs` (`rag_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
