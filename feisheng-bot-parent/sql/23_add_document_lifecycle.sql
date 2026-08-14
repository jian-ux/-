-- Add draft/release lifecycle, versioning, and conflict priority for documents.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'knowledge_set_key');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN knowledge_set_key VARCHAR(128) NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'document_version');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN document_version INT NOT NULL DEFAULT 1 AFTER knowledge_set_key',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'priority');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN priority INT NOT NULL DEFAULT 0 AFTER document_version',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'publish_status');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN publish_status VARCHAR(20) NOT NULL DEFAULT ''PUBLISHED'' AFTER priority',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'effective_from');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN effective_from DATETIME NULL AFTER publish_status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'effective_to');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN effective_to DATETIME NULL AFTER effective_from',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'supersedes_document_id');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN supersedes_document_id BIGINT NULL AFTER effective_to',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND COLUMN_NAME = 'published_at');
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_document ADD COLUMN published_at DATETIME NULL AFTER supersedes_document_id',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE bot_knowledge_document
SET knowledge_set_key = LEFT(LOWER(
        CASE
            WHEN COALESCE(NULLIF(file_name, ''), NULLIF(title, '')) LIKE '%.%'
                THEN SUBSTRING_INDEX(COALESCE(NULLIF(file_name, ''), title), '.', 1)
            ELSE COALESCE(NULLIF(file_name, ''), NULLIF(title, ''), CONCAT('document-', id))
        END), 128),
    document_version = COALESCE(document_version, 1),
    priority = COALESCE(priority, 0),
    publish_status = COALESCE(NULLIF(publish_status, ''), 'PUBLISHED')
WHERE knowledge_set_key IS NULL OR knowledge_set_key = '' OR publish_status IS NULL;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND INDEX_NAME = 'idx_knowledge_doc_lifecycle');
SET @ddl = IF(@index_exists = 0,
    'CREATE INDEX idx_knowledge_doc_lifecycle ON bot_knowledge_document (publish_status, effective_from, effective_to)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_document'
      AND INDEX_NAME = 'idx_knowledge_doc_version');
SET @ddl = IF(@index_exists = 0,
    'CREATE INDEX idx_knowledge_doc_version ON bot_knowledge_document (knowledge_set_key, document_version, publish_status)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
