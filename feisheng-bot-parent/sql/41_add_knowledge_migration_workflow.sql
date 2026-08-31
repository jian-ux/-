SET NAMES utf8mb4;
USE feisheng_bot_db;

CREATE TABLE IF NOT EXISTS bot_knowledge_migration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_document_id BIGINT NOT NULL,
    source_version_id BIGINT NULL,
    target_document_id BIGINT NULL,
    target_version_id BIGINT NOT NULL,
    knowledge_set_key VARCHAR(128) NOT NULL,
    source_content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    current_step VARCHAR(32) NULL,
    total_units INT NOT NULL DEFAULT 0,
    processed_units INT NOT NULL DEFAULT 0,
    conflict_units INT NOT NULL DEFAULT 0,
    approved_units INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    next_retry_at DATETIME NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    reviewer_id BIGINT NULL,
    reviewed_at DATETIME NULL,
    switched_at DATETIME NULL,
    error_message VARCHAR(1000) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_migration_source_hash (source_document_id, source_content_hash, target_version_id),
    INDEX idx_migration_knowledge_status (knowledge_set_key, status),
    INDEX idx_migration_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_knowledge_conflict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_job_id BIGINT NOT NULL,
    target_unit_id BIGINT NOT NULL,
    candidate_unit_id BIGINT NOT NULL,
    similarity DECIMAL(8,7) NULL,
    scope_relation VARCHAR(32) NULL,
    conflict_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    evidence JSON NULL,
    rule_result JSON NULL,
    llm_result JSON NULL,
    resolution VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',
    resolution_note VARCHAR(1000) NULL,
    reviewer_id BIGINT NULL,
    reviewed_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_migration_conflict_pair (migration_job_id, target_unit_id, candidate_unit_id),
    INDEX idx_conflict_job_status_severity (migration_job_id, status, severity),
    CONSTRAINT chk_migration_conflict_severity CHECK (severity IN ('BLOCKING','WARNING','INFO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_migration_job' AND COLUMN_NAME='updated_at');
SET @ddl = IF(@column_exists = 0, 'ALTER TABLE bot_knowledge_migration_job ADD COLUMN updated_at DATETIME NULL AFTER create_time', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_migration_job' AND COLUMN_NAME='update_time');
SET @ddl = IF(@column_exists = 1, 'UPDATE bot_knowledge_migration_job SET updated_at=COALESCE(updated_at, update_time)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE bot_knowledge_migration_job j JOIN bot_knowledge_document d ON d.id=j.target_document_id SET j.target_version_id=d.document_version WHERE j.target_version_id IS NULL AND j.target_document_id IS NOT NULL AND d.document_version IS NOT NULL;
SET @null_targets = (SELECT COUNT(*) FROM bot_knowledge_migration_job WHERE target_version_id IS NULL);
SET @ddl = IF(@null_targets = 0, 'ALTER TABLE bot_knowledge_migration_job MODIFY target_version_id BIGINT NOT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE bot_knowledge_migration_job SET updated_at=COALESCE(updated_at, CURRENT_TIMESTAMP);
SET @ddl = 'ALTER TABLE bot_knowledge_migration_job MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP';
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(@null_targets > 0, 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''Migration halted: target_version_id NULL rows require manual quarantine''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_conflict' AND COLUMN_NAME='status');
SET @ddl = IF(@column_exists = 0, 'ALTER TABLE bot_knowledge_conflict ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''PENDING'' AFTER conflict_type', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE bot_knowledge_conflict SET severity='INFO' WHERE severity IS NULL OR severity NOT IN ('BLOCKING','WARNING','INFO');

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bot_knowledge_migration_job' AND INDEX_NAME = 'idx_migration_knowledge_status');
SET @ddl = IF(@idx_exists = 0, 'CREATE INDEX idx_migration_knowledge_status ON bot_knowledge_migration_job (knowledge_set_key, status)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @idx_cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_migration_job' AND INDEX_NAME='idx_migration_status_updated');
SET @ddl = IF(@idx_cols IS NULL, 'CREATE INDEX idx_migration_status_updated ON bot_knowledge_migration_job (status, updated_at)', IF(@idx_cols <> 'status,updated_at', 'DROP INDEX idx_migration_status_updated ON bot_knowledge_migration_job', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @idx_cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_migration_job' AND INDEX_NAME='idx_migration_status_updated');
SET @ddl = IF(@idx_cols IS NULL, 'CREATE INDEX idx_migration_status_updated ON bot_knowledge_migration_job (status, updated_at)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @idx_cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_conflict' AND INDEX_NAME='idx_conflict_job_status_severity');
SET @ddl = IF(@idx_cols IS NULL, 'CREATE INDEX idx_conflict_job_status_severity ON bot_knowledge_conflict (migration_job_id, status, severity)', IF(@idx_cols <> 'migration_job_id,status,severity', 'DROP INDEX idx_conflict_job_status_severity ON bot_knowledge_conflict', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @idx_cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_conflict' AND INDEX_NAME='idx_conflict_job_status_severity');
SET @ddl = IF(@idx_cols IS NULL, 'CREATE INDEX idx_conflict_job_status_severity ON bot_knowledge_conflict (migration_job_id, status, severity)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @check_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='bot_knowledge_conflict' AND CONSTRAINT_NAME='chk_migration_conflict_severity');
SET @ddl = IF(@check_exists=0, 'ALTER TABLE bot_knowledge_conflict ADD CONSTRAINT chk_migration_conflict_severity CHECK (severity IN (''BLOCKING'',''WARNING'',''INFO''))', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
