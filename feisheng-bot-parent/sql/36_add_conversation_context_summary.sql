SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_conversation
  ADD COLUMN context_summary TEXT NULL AFTER title,
  ADD COLUMN summary_message_id BIGINT NULL AFTER context_summary,
  ADD COLUMN summary_updated_at DATETIME NULL AFTER summary_message_id,
  ADD INDEX idx_conversation_summary(summary_message_id, summary_updated_at);
