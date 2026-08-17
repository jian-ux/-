-- Publish customer-safe answers for the policy rows quarantined by migration 24.
-- The answers preserve verified facts, remove staff-only exceptions, and avoid
-- promising outcomes that still require customer-service review.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @governance_batch = 'customer-service-safe-policy-20260817';

INSERT IGNORE INTO bot_knowledge_governance_backup (
    batch_key, chunk_id, issue_code, original_content, original_qa_question,
    original_qa_answer, original_qa_group_key, original_qa_version,
    original_direct_answer_enabled, original_status, original_deleted
)
SELECT
    @governance_batch,
    id,
    'SAFE_POLICY_REWRITE',
    content,
    qa_question,
    qa_answer,
    qa_group_key,
    qa_version,
    direct_answer_enabled,
    status,
    deleted
FROM bot_knowledge_chunk
WHERE id IN (5514, 5633, 5677, 5680);

SET @usage_question = '点签可以在哪里使用？';
SET @usage_answer = '点签支持通过钉钉、微信公众号、微信小程序、PC 网页版、企业微信和短信签署链接使用。目前不提供独立手机 APP；手机用户可以通过微信公众号、微信小程序或短信签署链接办理相关操作。';

UPDATE bot_knowledge_chunk
SET qa_question = @usage_question,
    qa_answer = @usage_answer,
    content = CONCAT(@usage_question, CHAR(10), @usage_answer),
    section_path = @usage_question,
    char_count = CHAR_LENGTH(CONCAT(@usage_question, CHAR(10), @usage_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(@usage_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(@usage_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@usage_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5514
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @usage_answer);

SET @mobile_question = '签署电子合同必须在电脑上操作吗？手机可以吗？';
SET @mobile_answer = '不必只能在电脑上操作。电脑用户可以使用 PC 网页版；手机用户可以通过微信公众号、微信小程序或短信签署链接完成相关操作。目前不提供独立手机 APP。具体可用入口以收到的签署通知和点签官方页面为准。';

UPDATE bot_knowledge_chunk
SET qa_question = @mobile_question,
    qa_answer = @mobile_answer,
    content = CONCAT(@mobile_question, CHAR(10), @mobile_answer),
    section_path = @mobile_question,
    char_count = CHAR_LENGTH(CONCAT(@mobile_question, CHAR(10), @mobile_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(@mobile_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(@mobile_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@mobile_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5633
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @mobile_answer);

SET @renewal_question = '有效期限365天是什么意思？续套餐后，上一个套餐还能延期使用吗？';
SET @renewal_answer = '套餐有效期限为 365 天。剩余份数能否延期取决于套餐是否到期和剩余份数：到期前剩余不少于 50 份，可申请延长 1 次、延长 30 天；少于 50 份，可在原套餐到期前购买新套餐，并联系客服申请将剩余份数转入新套餐。套餐已过期且剩余份数超过 100 份的，可在过期后 30 天内申请延期其中 50%，延长 3 个月。以上申请均需客服核实，最终以当前套餐政策和审核结果为准。';

UPDATE bot_knowledge_chunk
SET qa_question = @renewal_question,
    qa_answer = @renewal_answer,
    content = CONCAT(@renewal_question, CHAR(10), @renewal_answer),
    section_path = @renewal_question,
    char_count = CHAR_LENGTH(CONCAT(@renewal_question, CHAR(10), @renewal_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(@renewal_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(@renewal_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@renewal_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5677
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @renewal_answer);

SET @transfer_question = '中途更换企业主体，已购套餐能否转移？';
SET @transfer_answer = '套餐与完成认证的企业主体绑定，原则上不直接跨企业转移。需要更换企业主体时，请先向点签客服提供原企业、新企业和剩余套餐份数等信息，由客服核实是否存在适用的处理方案；在审核完成前，不承诺可以转移，也不建议自行对原套餐进行其他操作。';

UPDATE bot_knowledge_chunk
SET qa_question = @transfer_question,
    qa_answer = @transfer_answer,
    content = CONCAT(@transfer_question, CHAR(10), @transfer_answer),
    section_path = @transfer_question,
    char_count = CHAR_LENGTH(CONCAT(@transfer_question, CHAR(10), @transfer_answer)),
    qa_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(@transfer_question), '[[:punct:][:space:]]+', '')), 256),
    qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(@transfer_question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(@transfer_answer), '[[:space:]]+', ' ')
    ), 256),
    qa_version = 2,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    status = 'APPROVED'
WHERE id = 5680
  AND deleted = 0
  AND (qa_version < 2 OR qa_answer <> @transfer_answer);
