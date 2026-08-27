SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_conversation
  ADD COLUMN dialog_state JSON NULL AFTER summary_updated_at,
  ADD COLUMN dialog_state_version BIGINT NOT NULL DEFAULT 0 AFTER dialog_state;
