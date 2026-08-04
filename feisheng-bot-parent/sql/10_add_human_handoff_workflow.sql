SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_conversation
  ADD COLUMN handoff_status VARCHAR(20) DEFAULT 'NONE' AFTER emotion_risk,
  ADD COLUMN assigned_agent_id BIGINT AFTER handoff_status,
  ADD COLUMN assigned_agent_name VARCHAR(100) AFTER assigned_agent_id,
  ADD COLUMN handoff_time DATETIME AFTER assigned_agent_name,
  ADD COLUMN accepted_time DATETIME AFTER handoff_time,
  ADD COLUMN resolved_time DATETIME AFTER accepted_time,
  ADD COLUMN last_human_reply_time DATETIME AFTER resolved_time,
  ADD INDEX idx_conversation_handoff(handoff_status, assigned_agent_id, update_time);

ALTER TABLE bot_ticket
  ADD COLUMN sla_deadline DATETIME AFTER assignee_id,
  ADD COLUMN accepted_time DATETIME AFTER sla_deadline,
  ADD COLUMN resolved_time DATETIME AFTER accepted_time,
  ADD COLUMN last_reply_time DATETIME AFTER resolved_time,
  ADD COLUMN resolution VARCHAR(1000) AFTER last_reply_time,
  ADD INDEX idx_ticket_assignment(status, assignee_id, sla_deadline);
