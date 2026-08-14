-- Persist document-import structure checks so abnormal files cannot be approved.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'quality_status'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN quality_status VARCHAR(20) NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'quality_message'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN quality_message VARCHAR(1000) NULL AFTER quality_status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'source_row_count'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN source_row_count INT NOT NULL DEFAULT 0 AFTER quality_message',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'detected_qa_count'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN detected_qa_count INT NOT NULL DEFAULT 0 AFTER source_row_count',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'invalid_row_count'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN invalid_row_count INT NOT NULL DEFAULT 0 AFTER detected_qa_count',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE bot_knowledge_document
SET quality_status = COALESCE(quality_status, 'PASSED'),
    quality_message = COALESCE(quality_message, '历史文档，未执行表格行级质量检查')
WHERE quality_status IS NULL;
