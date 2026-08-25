SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_unmatched_question
  ADD COLUMN trigger_types VARCHAR(255) NULL AFTER is_resolved,
  ADD COLUMN conversation_id BIGINT NULL AFTER trigger_types,
  ADD COLUMN last_answer_status VARCHAR(50) NULL AFTER conversation_id,
  ADD COLUMN last_source VARCHAR(50) NULL AFTER last_answer_status,
  ADD COLUMN last_confidence DECIMAL(8,4) NULL AFTER last_source,
  ADD COLUMN last_latency_ms INT NULL AFTER last_confidence,
  ADD COLUMN last_csat_score INT NULL AFTER last_latency_ms,
  ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP AFTER create_time,
  ADD INDEX idx_bad_case_trigger(trigger_types),
  ADD INDEX idx_bad_case_conversation(conversation_id),
  ADD INDEX idx_bad_case_update(update_time);

UPDATE bot_unmatched_question
SET trigger_types = 'NO_ANSWER'
WHERE trigger_types IS NULL OR trigger_types = '';

UPDATE sys_permission
SET name = '问题改进池'
WHERE permission = 'knowledge:unmatched:view';
