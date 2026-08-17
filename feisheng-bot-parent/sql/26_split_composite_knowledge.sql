-- Split or quarantine composite Q-A rows identified by the quality audit.
-- Valuable secondary facts become standalone Q-A rows; outbound scripts,
-- duplicates, and question/answer mismatches remain available in the backup.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @governance_batch = 'composite-qa-cleanup-20260817';

INSERT IGNORE INTO bot_knowledge_governance_backup (
    batch_key, chunk_id, issue_code, original_content, original_qa_question,
    original_qa_answer, original_qa_group_key, original_qa_version,
    original_direct_answer_enabled, original_status, original_deleted
)
SELECT
    @governance_batch,
    id,
    'COMPOSITE_QA_ANSWER',
    content,
    qa_question,
    qa_answer,
    qa_group_key,
    qa_version,
    direct_answer_enabled,
    status,
    deleted
FROM bot_knowledge_chunk
WHERE id IN (
    5518, 5519, 5520, 5521, 5522, 5531, 5554, 5568, 5614, 5651, 5657,
    5669, 5679, 5685, 5686, 5689, 5693, 5694, 5705, 5706, 5715
);

CREATE TEMPORARY TABLE tmp_composite_qa_rewrite (
    chunk_id BIGINT PRIMARY KEY,
    question VARCHAR(1000) NOT NULL,
    answer LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_composite_qa_rewrite (chunk_id, question, answer) VALUES
(5518,
 '电子合同可以帮我实现什么？',
 '点签电子合同可以帮助企业在线完成合同发起、身份核验、在线签署、签章管理、存证和归档，支持异地协同，减少打印、邮寄和人工跟踪。具体可用功能、入口和服务范围以企业当前套餐及使用端为准。电子合同的法律效力仍需结合签署主体、签署方式、合同内容和适用法律等具体情况判断。'),
(5531,
 '合同发起后还未签署，发现附件漏传了，能补充上传附件吗？',
 '合同发出后，如果尚无任何一方签署，发起人需要先撤回原合同，补齐附件后重新发起，不能直接在已经发出的合同中追加附件。如果已经有一方完成签署，不应直接修改原合同，请先根据当前合同状态与相关方确认终止或补充处理方式，再重新发起。'),
(5554,
 '系统会替我们验证对方签章的真实性吗？',
 '点签会通过个人和企业身份核验、数字证书、数字签名及签署过程记录，帮助核验签署主体并验证签署后的文件是否被修改。这些机制用于提高身份和签章验证的可靠性，但不能脱离具体签署资料作绝对保证；如对某次签署有疑问，应进一步核对数字证书和签署记录。'),
(5568,
 '签署的流程是怎么样的？',
 '点签签署流程通常包括：首次使用时完成个人或企业认证；企业管理员配置并授权印章；发起人上传合同、填写接收方信息并发起；接收方通过短信、微信公众号、微信小程序、PC 网页版或钉钉等入口核验身份并签署；需要发起方盖章的，再由发起方完成最终签署。合同何时生效应以合同约定和适用法律为准。'),
(5614,
 '合同中可以实现多处盖章吗？',
 '可以。发起人可以在同一份合同的多个指定位置设置签章控件，实现多处盖章。实际可选择的印章和盖章人员受企业印章授权及用印权限控制。'),
(5651,
 '多人签署的合同中，有一方拒签后，其他已经签署的方会收到拒签通知吗？',
 '一方拒签后，合同状态会更新为已拒签，发起人和相关签署方会收到拒签通知；通知中可包含拒签人、拒签时间和拒签原因。若仍需继续签署，发起人应与相关方确认后修改或重新创建合同并重新发起。具体通知渠道以企业和用户的消息设置为准。'),
(5669,
 '买完之后明年会不会涨价？',
 '目前无法承诺下一年度的套餐价格是否调整。购买和续费时适用的价格，应以当时点签官网、套餐管理页面或正式报价显示为准；如套餐即将到期，建议在续费前确认最新价格和套餐权益。'),
(5679,
 '后期如果不在续费了，账号还能登录使用吗？',
 '不再续费后，点签账号仍可正常登录，已经完成签署的合同会保留在企业账户中，可继续查阅和下载。没有可用合同份数或有效套餐时，不能继续发起新的合同；需要继续签署时，可再购买适用套餐。'),
(5685,
 '如有几家子公司，能否实现共同管理？',
 '点签支持同一用户加入并管理多家已认证企业，使用时需要切换到对应企业身份。通过钉钉使用时，各子公司需要分别建立钉钉组织并在各自工作台开通点签应用，再切换到相应组织操作。可查看范围和套餐共享能力以当前账号权限及套餐权益为准。'),
(5686,
 '发起合同时公司抬头可以更换吗？',
 '发起合同时使用的企业抬头与当前已认证企业身份绑定，不能在合同中临时改成其他公司的抬头。一个账号管理多家企业时，需要先切换到目标企业身份再发起合同；通过钉钉使用时，还需要切换到对应钉钉组织。'),
(5689,
 '“用印管理员” 能发起合同吗？',
 '默认不能。用印管理员的主要权限是管理印章授权和审核用印申请，不默认包含合同发起权限。如确实需要该人员发起合同，应由企业管理员根据岗位职责另行配置具备发起权限的角色。'),
(5693,
 '员工离职后，其发起的合同如何转移？',
 '管理员可在员工管理中禁用离职员工账号，并将其名下待处理合同重新分配给接替员工。已经签署完成的企业合同仍保留在企业账户中，不会随员工账号停用而删除；后续新合同应由接替员工发起。'),
(5694,
 '钉钉工作台中，找不到应用？',
 '员工在钉钉工作台找不到点签应用时，需要由企业的钉钉主管理员进入钉钉管理后台，在工作台的应用管理中检查点签应用的可见范围和使用权限，确认该员工或部门已被加入可见范围后，再让员工重新进入工作台查看。');

UPDATE bot_knowledge_chunk chunk
JOIN tmp_composite_qa_rewrite rewrite ON rewrite.chunk_id = chunk.id
SET chunk.qa_question = rewrite.question,
    chunk.qa_answer = rewrite.answer,
    chunk.content = CONCAT(rewrite.question, CHAR(10), rewrite.answer),
    chunk.section_path = rewrite.question,
    chunk.char_count = CHAR_LENGTH(CONCAT(rewrite.question, CHAR(10), rewrite.answer)),
    chunk.qa_key = SHA2(LOWER(REGEXP_REPLACE(
        TRIM(rewrite.question), '[[:punct:][:space:]]+', '')), 256),
    chunk.qa_group_key = SHA2(CONCAT(
        LOWER(REGEXP_REPLACE(TRIM(rewrite.question), '[[:punct:][:space:]]+', '')), '|',
        REGEXP_REPLACE(TRIM(rewrite.answer), '[[:space:]]+', ' ')
    ), 256),
    chunk.qa_version = GREATEST(COALESCE(chunk.qa_version, 1) + 1, 2),
    chunk.direct_answer_enabled = 0,
    chunk.embedding = NULL,
    chunk.embedding_model = NULL,
    chunk.embedding_version = NULL,
    chunk.embedding_dimensions = NULL,
    chunk.embedding_content_hash = NULL,
    chunk.status = 'APPROVED'
WHERE chunk.deleted = 0
  AND (chunk.qa_question <> rewrite.question
    OR chunk.qa_answer <> rewrite.answer
    OR chunk.status <> 'APPROVED');

DROP TEMPORARY TABLE tmp_composite_qa_rewrite;

-- Four duplicated chunks repeat one oversized answer. The remaining four rows
-- are outbound scripts or question/answer mismatches, not customer FAQs.
UPDATE bot_knowledge_chunk
SET status = 'PENDING',
    direct_answer_enabled = 0
WHERE id IN (5519, 5520, 5521, 5522, 5657, 5705, 5706, 5715)
  AND deleted = 0
  AND status = 'APPROVED';

SET @split_document_id = 65;

SET @split_question = '将纸质合同上传归档后，能关联到对应的电子合同吗？';
SET @split_answer = '可以。进入合同管理中的纸质合同归档功能，上传纸质合同扫描件后，选择或填写对应的电子合同编号并确认归档。关联完成后，可在相应合同中查看归档文件。具体入口、文件格式和可关联范围以当前产品页面为准。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10001, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);

SET @split_question = '点签人事合同和点签电子合同需要分开购买套餐吗？';
SET @split_answer = '不需要。已购买的合同份数可用于点签人事合同和电子合同两个业务入口。具体可用范围以当前套餐权益和使用端显示为准。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10002, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);

SET @split_question = '多次购买的套餐费用可以合并开一张电子发票吗？';
SET @split_answer = '如需合并开票，请在开票前联系点签客服，提供相关订单和开票信息，由客服核实是否满足合并开票条件。是否可以合并以及最终开票方式，以订单情况和客服核实结果为准。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10003, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);

SET @split_question = '员工在劳动合同到期前离职，如何在线办理解约？';
SET @split_answer = '员工在劳动合同到期前离职时，应先依据双方协商结果和合同约定确认解除方式。双方达成一致后，可在对应合同中发起解除或终止流程并由对方确认，系统保留相关操作记录。具体入口和所需手续以当前产品页面、合同约定及适用法律为准；存在争议时，建议咨询人事或法律专业人员。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10004, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);

SET @split_question = '企业微信端如何批量导入员工信息？';
SET @split_answer = '在企业微信中进入点签应用的员工管理，选择批量导入并下载员工信息模板。按模板填写员工姓名、手机号、部门和角色等信息后上传，系统会校验数据；校验失败的记录需要根据提示修改后重新导入。具体模板字段和入口以当前页面为准。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10005, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);

SET @split_question = '管理员能查看员工的合同签署操作日志吗？';
SET @split_answer = '管理员可在控制台的操作日志中，按员工等条件查看权限范围内的合同发起、签署和拒签等操作及操作时间。具体可查看范围、日志保存期限和是否支持导出，以当前版本及管理员权限为准。';
INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @split_document_id, 10006, CONCAT(@split_question, CHAR(10), @split_answer),
       @split_question, CHAR_LENGTH(CONCAT(@split_question, CHAR(10), @split_answer)),
       'governance-v1', 'QA', @split_question, @split_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@split_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@split_answer), '[[:space:]]+', ' ')
       ), 256),
       1, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @split_document_id AND qa_question = @split_question AND deleted = 0
);
