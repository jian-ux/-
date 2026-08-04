SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Phase 3: image OCR and screenshot question answering metadata.
ALTER TABLE bot_knowledge_document
  ADD COLUMN media_type VARCHAR(20) NOT NULL DEFAULT 'DOCUMENT'
    COMMENT 'DOCUMENT/IMAGE' AFTER file_type,
  ADD COLUMN source_scope VARCHAR(20) NOT NULL DEFAULT 'KNOWLEDGE'
    COMMENT 'KNOWLEDGE/CHAT' AFTER media_type,
  ADD COLUMN ocr_status VARCHAR(20) DEFAULT NULL
    COMMENT 'PROCESSING/COMPLETED/FAILED' AFTER source_scope,
  ADD COLUMN ocr_text LONGTEXT AFTER ocr_status,
  ADD COLUMN ocr_language VARCHAR(50) DEFAULT NULL AFTER ocr_text,
  ADD COLUMN ocr_error VARCHAR(1000) DEFAULT NULL AFTER ocr_language,
  ADD COLUMN expires_at DATETIME DEFAULT NULL AFTER ocr_error,
  ADD INDEX idx_media_scope (media_type, source_scope),
  ADD INDEX idx_ocr_status (ocr_status),
  ADD INDEX idx_expires_at (expires_at);
