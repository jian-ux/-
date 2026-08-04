SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_chunk'
      AND COLUMN_NAME = 'deleted'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_chunk ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER create_time',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS bot_knowledge_semantic_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    unit_key CHAR(64) NOT NULL,
    unit_type VARCHAR(32) NOT NULL DEFAULT 'FACT',
    question VARCHAR(1000) NULL,
    statement LONGTEXT NOT NULL,
    intent VARCHAR(128) NULL,
    entities_json JSON NULL,
    conditions_json JSON NULL,
    exclusions_json JSON NULL,
    query_variants_json JSON NULL,
    evidence_chunk_ids_json JSON NOT NULL,
    source_spans_json JSON NULL,
    metadata_json JSON NULL,
    extraction_confidence DECIMAL(6,5) NULL,
    extractor_model VARCHAR(200) NULL,
    prompt_version VARCHAR(64) NULL,
    schema_version VARCHAR(64) NULL,
    source_hash CHAR(64) NOT NULL,
    embedding LONGTEXT NULL,
    embedding_model VARCHAR(200) NULL,
    embedding_version VARCHAR(64) NULL,
    embedding_dimensions INT NULL,
    embedding_content_hash VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    review_reason VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_semantic_unit_source (document_id, source_hash, unit_key),
    INDEX idx_semantic_unit_status (status, deleted),
    INDEX idx_semantic_unit_document (document_id, deleted),
    INDEX idx_semantic_unit_category (category_id, status, deleted),
    INDEX idx_semantic_unit_embedding_version (embedding_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_semantic_unit'
      AND COLUMN_NAME = 'reviewed_by'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_semantic_unit ADD COLUMN reviewed_by BIGINT NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_semantic_unit'
      AND COLUMN_NAME = 'reviewed_at'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_semantic_unit ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_semantic_unit'
      AND COLUMN_NAME = 'review_reason'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE bot_knowledge_semantic_unit ADD COLUMN review_reason VARCHAR(500) NULL AFTER reviewed_at',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
