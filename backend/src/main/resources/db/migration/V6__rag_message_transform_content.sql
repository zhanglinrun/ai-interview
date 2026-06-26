-- RAG 聊天消息新增 transform_content 列：存查询改写器（InterviewQueryTransformer）改写后的查询文本，
-- 改写完成后由虚拟线程异步回写（对齐 know-engine 的 chat_message.transform_content）。
-- 用于排查改写质量 / 检索召回差异，不参与生成。

ALTER TABLE public.rag_chat_messages ADD COLUMN IF NOT EXISTS transform_content text;
