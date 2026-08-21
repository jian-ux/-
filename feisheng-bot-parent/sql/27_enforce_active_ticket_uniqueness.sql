SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Keep closed/resolved history while preventing two live handoff tickets for
-- the same conversation. MySQL permits multiple NULL values in a unique key,
-- so terminal tickets do not conflict with one another.
ALTER TABLE bot_ticket
  ADD COLUMN active_conversation_id BIGINT
    GENERATED ALWAYS AS (
      IF(deleted = 0 AND status IN ('pending', 'processing'), conversation_id, NULL)
    ) STORED,
  ADD UNIQUE KEY uk_ticket_active_conversation(active_conversation_id);
