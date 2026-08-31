SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_customer
  ADD COLUMN long_term_summary TEXT NULL AFTER profile_updated_at,
  ADD COLUMN long_term_summary_updated_at DATETIME NULL AFTER long_term_summary;

CREATE TABLE IF NOT EXISTS bot_customer_memory (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  memory_key VARCHAR(100) NOT NULL,
  memory_value VARCHAR(1000) NOT NULL,
  source VARCHAR(50) NOT NULL,
  confidence DECIMAL(5,4) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  source_message_id BIGINT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_customer_memory(customer_id, memory_key),
  INDEX idx_customer_memory_status(customer_id, status),
  INDEX idx_customer_memory_source_message(source_message_id),
  CONSTRAINT fk_customer_memory_customer FOREIGN KEY (customer_id) REFERENCES bot_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_customer_media (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  source_message_id BIGINT NULL,
  media_type VARCHAR(50) NOT NULL,
  object_key VARCHAR(500) NULL,
  ocr_text TEXT NULL,
  metadata JSON NULL,
  trust_level VARCHAR(20) NOT NULL DEFAULT 'UNTRUSTED',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  INDEX idx_customer_media_customer(customer_id, create_time),
  INDEX idx_customer_media_source_message(source_message_id),
  CONSTRAINT fk_customer_media_customer FOREIGN KEY (customer_id) REFERENCES bot_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
