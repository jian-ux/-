-- Controlled direct answers for reviewed structured Q-A document chunks.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'content_type'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN content_type VARCHAR(20) NOT NULL DEFAULT ''TEXT'' AFTER chunk_strategy_version',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'qa_question'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN qa_question VARCHAR(1000) NULL AFTER content_type',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'qa_answer'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN qa_answer LONGTEXT NULL AFTER qa_question',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'qa_key'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN qa_key CHAR(64) NULL AFTER qa_answer',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'qa_group_key'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN qa_group_key CHAR(64) NULL AFTER qa_key',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'qa_version'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN qa_version INT NOT NULL DEFAULT 1 AFTER qa_group_key',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'direct_answer_enabled'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN direct_answer_enabled TINYINT NOT NULL DEFAULT 0 AFTER qa_version',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND INDEX_NAME = 'idx_chunk_qa_direct'
);
SET @ddl = IF(@index_exists = 0,
    'CREATE INDEX idx_chunk_qa_direct ON bot_knowledge_chunk (qa_key, qa_version, direct_answer_enabled, status)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Existing chunk-v2 Q-A units store the question in section_path and repeat it at
-- the beginning of content. Backfill metadata conservatively; direct output stays opt-in.
UPDATE bot_knowledge_chunk
SET content_type = 'QA',
    qa_question = TRIM(section_path),
    qa_answer = TRIM(SUBSTRING(content, CHAR_LENGTH(section_path) + 2)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(section_path), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(section_path), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(SUBSTRING(content, CHAR_LENGTH(section_path) + 2)), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 1,
    direct_answer_enabled = 0
WHERE (content_type IS NULL OR content_type = 'TEXT')
  AND section_path IS NOT NULL
  AND section_path <> ''
  AND content LIKE CONCAT(section_path, CHAR(10), '%');
