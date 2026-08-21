SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Publish two small, source-backed Q&A units so retrieval does not rely on the
-- older mixed paragraph that combines product versions and launch methods.
SET @version_question = '点签不同产品版本有什么区别？';
SET @version_answer = '当前知识库已确认的版本差异包括：专业版支持合同到期提醒；高级版支持在钉钉中关联审批流程，审批通过后可自动盖章。其他功能、可用范围和套餐权益以当前点签官网及套餐管理页面显示为准。';

INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count
)
SELECT 0, @version_question, @version_answer,
       '产品版本,版本区别,专业版,高级版,到期提醒,钉钉审批', 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_item
    WHERE question = @version_question AND deleted = 0
);

UPDATE bot_knowledge_item
SET answer = @version_answer,
    keywords = '产品版本,版本区别,专业版,高级版,到期提醒,钉钉审批',
    status = 1,
    update_time = CURRENT_TIMESTAMP
WHERE question = @version_question AND deleted = 0;

SET @launch_question = '发起合同有几种方式？';
SET @launch_answer = '您好，根据点签平台的设置，发起合同主要有两种方式：\n\n1. 上传文件发起：您事先将合同内容填写完成后，直接上传合同文件进行发起。\n\n2. 模板发起：将企业内部的合同模板在 PC 端上传至点签平台，设定模板内应签署的区域后进行保存。发起合同时，填写合同相应的接收方信息后可直接发起合同。\n\n平台通用模板属于模板发起，不单独计算为第三种方式。合同完成盖章后，双方可在账号中查阅和下载存档。';

INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count
)
SELECT 0, @launch_question, @launch_answer,
       '发起合同,合同发起,发起方式,上传文件发起,模板发起,通用模板', 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_item
    WHERE question = @launch_question AND deleted = 0
);

UPDATE bot_knowledge_item
SET answer = @launch_answer,
    keywords = '发起合同,合同发起,发起方式,上传文件发起,模板发起,通用模板',
    status = 1,
    update_time = CURRENT_TIMESTAMP
WHERE question = @launch_question AND deleted = 0;

SET @document_id = 65;
SET @chunk_strategy = 'governance-v1';

INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @document_id, 10007, CONCAT(@version_question, CHAR(10), @version_answer),
       @version_question, CHAR_LENGTH(CONCAT(@version_question, CHAR(10), @version_answer)),
       @chunk_strategy, 'QA', @version_question, @version_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@version_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@version_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@version_answer), '[[:space:]]+', ' ')
       ), 256),
       2, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @document_id AND qa_question = @version_question AND deleted = 0
);

INSERT INTO bot_knowledge_chunk (
    document_id, chunk_index, content, section_path, char_count,
    chunk_strategy_version, content_type, qa_question, qa_answer, qa_key,
    qa_group_key, qa_version, direct_answer_enabled, status, create_time, deleted
)
SELECT @document_id, 10008, CONCAT(@launch_question, CHAR(10), @launch_answer),
       @launch_question, CHAR_LENGTH(CONCAT(@launch_question, CHAR(10), @launch_answer)),
       @chunk_strategy, 'QA', @launch_question, @launch_answer,
       SHA2(LOWER(REGEXP_REPLACE(TRIM(@launch_question), '[[:punct:][:space:]]+', '')), 256),
       SHA2(CONCAT(
           LOWER(REGEXP_REPLACE(TRIM(@launch_question), '[[:punct:][:space:]]+', '')), '|',
           REGEXP_REPLACE(TRIM(@launch_answer), '[[:space:]]+', ' ')
       ), 256),
       2, 0, 'APPROVED', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM bot_knowledge_chunk
    WHERE document_id = @document_id AND qa_question = @launch_question AND deleted = 0
);
