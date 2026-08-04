SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Add MinIO storage fields to bot_knowledge_document
ALTER TABLE bot_knowledge_document
  ADD COLUMN bucket_name VARCHAR(100) DEFAULT 'feisheng-docs' COMMENT 'MinIO bucket name' AFTER file_path,
  ADD COLUMN object_key VARCHAR(500) DEFAULT '' COMMENT 'MinIO object key' AFTER bucket_name,
  ADD COLUMN file_type VARCHAR(50) DEFAULT '' COMMENT 'File type: docx/pdf/txt/csv' AFTER object_key;

-- Mark file_path as deprecated (keep for migration, remove later)
ALTER TABLE bot_knowledge_document
  MODIFY COLUMN file_path VARCHAR(500) DEFAULT '' COMMENT 'Deprecated: use object_key instead';
