SET NAMES utf8mb4;
USE feisheng_bot_db;

CREATE TABLE IF NOT EXISTS bot_memory_outbox_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(50) NOT NULL,
  dedup_key VARCHAR(255) NOT NULL,
  customer_id BIGINT NULL,
  conversation_id BIGINT NULL,
  source_message_id BIGINT NULL,
  payload JSON NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  locked_until DATETIME NULL,
  last_error_code VARCHAR(80) NULL,
  last_error_message VARCHAR(500) NULL,
  processed_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_memory_outbox_dedup(dedup_key),
  INDEX idx_memory_outbox_pending(status, available_at),
  INDEX idx_memory_outbox_lease(status, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
