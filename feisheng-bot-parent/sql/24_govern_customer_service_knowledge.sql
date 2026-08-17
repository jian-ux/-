-- Quarantine contradictory or policy-dependent knowledge and clean safe customer-facing answers.
-- The migration is repeatable and preserves the original rows for manual rollback or review.
SET NAMES utf8mb4;
USE feisheng_bot_db;

CREATE TABLE IF NOT EXISTS bot_knowledge_governance_backup (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_key VARCHAR(100) NOT NULL,
    chunk_id BIGINT NOT NULL,
    issue_code VARCHAR(100) NOT NULL,
    original_content LONGTEXT,
    original_qa_question VARCHAR(1000),
    original_qa_answer LONGTEXT,
    original_qa_group_key CHAR(64),
    original_qa_version INT,
    original_direct_answer_enabled TINYINT,
    original_status VARCHAR(20),
    original_deleted TINYINT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_governance_batch_chunk (batch_key, chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @governance_batch = 'customer-service-quality-20260817';

INSERT IGNORE INTO bot_knowledge_governance_backup (
    batch_key, chunk_id, issue_code, original_content, original_qa_question,
    original_qa_answer, original_qa_group_key, original_qa_version,
    original_direct_answer_enabled, original_status, original_deleted
)
SELECT
    @governance_batch,
    id,
    CASE
        WHEN id IN (5514, 5633) THEN 'MOBILE_CHANNEL_CONFLICT'
        WHEN id IN (5677, 5680) THEN 'POLICY_OR_EXCEPTION_CONFLICT'
        ELSE 'INTERNAL_NOTE_EXPOSURE'
    END,
    content,
    qa_question,
    qa_answer,
    qa_group_key,
    qa_version,
    direct_answer_enabled,
    status,
    deleted
FROM bot_knowledge_chunk
WHERE id IN (5514, 5633, 5677, 5680, 5683, 5708);

-- These facts require an explicit product or policy decision. Keep them in the
-- review queue but remove them from all approved retrieval paths for now.
UPDATE bot_knowledge_chunk
SET status = 'PENDING',
    direct_answer_enabled = 0
WHERE id IN (5514, 5633, 5677, 5680)
  AND deleted = 0
  AND COALESCE(qa_version, 1) = 1
  AND status = 'APPROVED';

SET @purchase_question = '免费合同用完了，怎么买新的套餐？';
SET @purchase_answer = '免费合同份数用完后，可进入“账户中心”，选择“套餐管理”查看当前可购买的套餐。选择适合企业的套餐并按页面提示完成支付；支付成功且份数到账后，即可继续发起合同。具体套餐份数、价格、支付方式和到账状态以“套餐管理”页面显示为准。';

-- Remove the unrelated multi-company question and the internal invoice note.
UPDATE bot_knowledge_chunk
SET qa_question = @purchase_question,
    qa_answer = @purchase_answer,
    content = CONCAT(@purchase_question, CHAR(10), @purchase_answer),
    section_path = @purchase_question,
    char_count = CHAR_LENGTH(CONCAT(@purchase_question, CHAR(10), @purchase_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(@purchase_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(@purchase_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@purchase_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5683
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @purchase_answer);

SET @legal_representative_question = '更换法人认证';
SET @legal_representative_answer = '更换企业法人时，需要先由新法人完成实名认证。认证完成后，请将新法人和原法人的姓名、手机号提交给点签客服，由客服协助办理法人变更。是否需要补充其他材料及实际处理结果，以客服核实为准。';

-- Keep the customer action while removing the internal technical handoff note.
UPDATE bot_knowledge_chunk
SET qa_question = @legal_representative_question,
    qa_answer = @legal_representative_answer,
    content = CONCAT(@legal_representative_question, CHAR(10), @legal_representative_answer),
    section_path = @legal_representative_question,
    char_count = CHAR_LENGTH(CONCAT(
        @legal_representative_question, CHAR(10), @legal_representative_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(
        TRIM(@legal_representative_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(
            TRIM(@legal_representative_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@legal_representative_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5708
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @legal_representative_answer);
