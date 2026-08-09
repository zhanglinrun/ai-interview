-- AI Interview Text2SQL 的只读 Schema 检查脚本。
--
-- 应用运行时由 RagSqlSchemaService 使用参数化 information_schema 查询，
-- 该文件用于本地排查、初始化检查和评测准备，不会被应用当作可执行迁移脚本。
-- 只允许查看当前数据库中已经纳入白名单的业务表。

SELECT
    c.table_name,
    t.table_comment,
    c.ordinal_position,
    c.column_name,
    c.column_type,
    c.is_nullable,
    c.column_key,
    c.column_comment
FROM information_schema.columns c
JOIN information_schema.tables t
  ON t.table_schema = c.table_schema
 AND t.table_name = c.table_name
WHERE c.table_schema = DATABASE()
  AND c.table_name IN (
      'documents',
      'document_versions',
      'document_segments',
      'resumes',
      'resume_analyses',
      'interview_sessions',
      'interview_answers',
      'chat_sessions',
      'chat_messages',
      'chat_memories'
  )
ORDER BY c.table_name, c.ordinal_position;

