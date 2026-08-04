SET NAMES utf8mb4;
USE feisheng_bot_db;
-- RAG: 为AI回复日志增加RAG标记列
ALTER TABLE bot_ai_reply_log ADD COLUMN rag_used TINYINT(1) DEFAULT 0 COMMENT '是否使用了RAG上下文' AFTER cited_chunk_ids;
