-- ============================================================================
-- 升级脚本：2026-07（BYOK 用户级 LLM Provider 表 user_llm_provider）
--
-- 适用对象：在本次升级前已初始化过数据的存量库。
--   schema.sql 只在空库初始化时执行（CREATE TABLE IF NOT EXISTS 不会新建已存在库缺失的表？
--   实际上会新建缺失的表，但存量库通常不会重跑 schema.sql），存量库需手动执行本脚本补建新表。
--   全新库（或删卷重建）由 schema.sql 直接建表，无需执行。
--
-- 覆盖范围：
--   新增 user_llm_provider 表——每个用户一条「我的模型」（base_url + 加密 api_key +
--   chat_model[+temperature]），仅 Chat/LLM 走 per-user 解析，Embedding 仍走全局默认 Provider。
--
-- 不执行的后果：
--   存量库缺该表时，per-user Provider CRUD（/api/llm-provider/mine 读/存/删/测试）
--   与 Registry.getUserChatModel/getUserStreamingChatModel 会在访问 user_llm_provider 时报
--   「Table doesn't exist」。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS，重复执行安全。
-- 执行方式（本机 dev compose 示例，端口/库名/账号以 .env 为准）：
--   mysql -h127.0.0.1 -P33306 -u<MYSQL_USER> -p <MYSQL_DB> \
--     < backend/src/main/resources/sql/upgrade/2026-07-byok-user-llm-provider.sql
-- ============================================================================

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

SELECT '2026-07 升级脚本执行完成（BYOK user_llm_provider 表）' AS done;
