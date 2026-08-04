-- First production pass for answer completeness and fixed-reply reduction.
SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Keep this migration repeatable because it may be reapplied during local recovery.
SET @direct_answer_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bot_knowledge_item'
      AND COLUMN_NAME = 'direct_answer_enabled'
);
SET @direct_answer_ddl = IF(
    @direct_answer_column_exists = 0,
    'ALTER TABLE bot_knowledge_item ADD COLUMN direct_answer_enabled TINYINT NOT NULL DEFAULT 0 COMMENT ''仅允许审核后的原子FAQ直接返回'' AFTER hit_count',
    'SELECT 1'
);
PREPARE direct_answer_stmt FROM @direct_answer_ddl;
EXECUTE direct_answer_stmt;
DEALLOCATE PREPARE direct_answer_stmt;

-- Direct output is opt-in. Exact FAQ hits without this flag are synthesized by
-- the model together with other retrieved evidence.
UPDATE bot_knowledge_item
SET direct_answer_enabled = 0
WHERE direct_answer_enabled IS NULL OR direct_answer_enabled <> 0;

-- The official website URL is an atomic, reviewed fact and is safe to return as-is.
UPDATE bot_knowledge_item
SET direct_answer_enabled = 1
WHERE question = '点签官网地址'
  AND status = 1
  AND deleted = 0;

-- Restore the reviewed atomic advantage FAQ when an older import marked it deleted.
UPDATE bot_knowledge_item
SET answer = '点签的优势主要体现在五个方面：1. 全流程电子签约，覆盖合同发起、身份认证、在线签署、签章管理、存证和归档；2. 安全合规，采用数字签名、数据加密和多重身份核验，保障签署过程和合同文件可追溯、可验证；3. 多端使用与系统集成，支持微信、PC、钉钉、企业微信等渠道，并可通过 OpenAPI 接入现有 OA、ERP 等系统；4. 交付方式灵活，支持 SaaS、OpenAPI 和定制化开发，适配不同企业流程；5. 提供一对一服务，并可提供海南本地化支持。具体选型可以结合签署量、使用渠道和是否需要系统对接来判断。',
    keywords = '点签优势,产品优势,产品的优势,平台优势,产品特点,平台特点,为什么选择点签,点签有什么特点',
    status = 1,
    deleted = 0,
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE question = '点签有哪些产品优势？';

-- If the reviewed row never existed, repair the malformed imported row in place.
UPDATE bot_knowledge_item
SET question = '点签有哪些产品优势？',
    answer = '点签的优势主要体现在五个方面：1. 全流程电子签约，覆盖合同发起、身份认证、在线签署、签章管理、存证和归档；2. 安全合规，采用数字签名、数据加密和多重身份核验，保障签署过程和合同文件可追溯、可验证；3. 多端使用与系统集成，支持微信、PC、钉钉、企业微信等渠道，并可通过 OpenAPI 接入现有 OA、ERP 等系统；4. 交付方式灵活，支持 SaaS、OpenAPI 和定制化开发，适配不同企业流程；5. 提供一对一服务，并可提供海南本地化支持。具体选型可以结合签署量、使用渠道和是否需要系统对接来判断。',
    keywords = '点签优势,产品优势,产品的优势,平台优势,产品特点,平台特点,为什么选择点签,点签有什么特点',
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE question = '点签优势'
  AND NOT EXISTS (
      SELECT 1
      FROM (SELECT question, status, deleted FROM bot_knowledge_item) reviewed_advantage
      WHERE reviewed_advantage.question = '点签有哪些产品优势？'
        AND reviewed_advantage.status = 1
        AND reviewed_advantage.deleted = 0
  );

-- Disable the malformed mixed answer once the reviewed atomic FAQ is available.
UPDATE bot_knowledge_item
SET status = 0,
    deleted = 1,
    direct_answer_enabled = 0,
    update_time = CURRENT_TIMESTAMP
WHERE question = '点签优势'
  AND EXISTS (
      SELECT 1
      FROM (SELECT question, status, deleted FROM bot_knowledge_item) reviewed_advantage
      WHERE reviewed_advantage.question = '点签有哪些产品优势？'
        AND reviewed_advantage.status = 1
        AND reviewed_advantage.deleted = 0
  );

-- Repair the two missing list items and keep each function explicit.
UPDATE bot_knowledge_item
SET answer = '点签电子合同主要包含 7 类功能：1. 企业印章管理，可对印章进行集中管理、授权和使用控制；2. 身份认证与签署安全，对操作人身份进行核验并记录合同发起、签署过程；3. 组织与权限管理，可按企业岗位和职责配置不同操作权限；4. 合同存储与管理，对合同文件加密存储，并支持查阅和下载；5. 多企业管理，同一账号可按权限切换管理多家公司；6. 合同模板管理，可上传企业合同模板、设置签署区域并复用发起；7. 合同状态提醒，可接收认证、发起、填写、拒签和签约等状态通知。具体入口和可用范围以当前使用端及套餐权限为准。',
    keywords = '点签功能,7大功能,七大功能,产品功能,平台功能,主要功能,能做什么',
    direct_answer_enabled = 0,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE question = '点签电子合同主要包含的7大功能'
  AND deleted = 0;

-- Merge exact duplicate answers imported under punctuation or pronoun variants.
UPDATE bot_knowledge_item
SET status = 0, deleted = 1, direct_answer_enabled = 0, update_time = CURRENT_TIMESTAMP
WHERE question = '什么是电子合同'
  AND EXISTS (
      SELECT 1 FROM (SELECT question, status, deleted FROM bot_knowledge_item) canonical
      WHERE canonical.question = '什么是电子合同？'
        AND canonical.status = 1 AND canonical.deleted = 0
  );

UPDATE bot_knowledge_item
SET status = 0, deleted = 1, direct_answer_enabled = 0, update_time = CURRENT_TIMESTAMP
WHERE question = '我们主要是做什么的？'
  AND EXISTS (
      SELECT 1 FROM (SELECT question, status, deleted FROM bot_knowledge_item) canonical
      WHERE canonical.question = '你们主要是做什么的？'
        AND canonical.status = 1 AND canonical.deleted = 0
  );

UPDATE bot_knowledge_item
SET status = 0, deleted = 1, direct_answer_enabled = 0, update_time = CURRENT_TIMESTAMP
WHERE question = '发起合同有两种方式。'
  AND EXISTS (
      SELECT 1 FROM (SELECT question, status, deleted FROM bot_knowledge_item) canonical
      WHERE canonical.question = '发起合同有几种方式？'
        AND canonical.status = 1 AND canonical.deleted = 0
  );

-- Comparison questions should enter retrieval and synthesis. Safety still logs the
-- hit, while the answer stays grounded in our own reviewed product facts.
UPDATE bot_forbidden_rule
SET action = 'LOG_ONLY',
    reply_text = NULL,
    description = '竞品或平台比较 → 记录并进入知识问答',
    update_time = CURRENT_TIMESTAMP
WHERE rule_type = 'FORBIDDEN_TOPIC'
  AND (description LIKE '%竞品%' OR pattern LIKE '%竞品%' OR pattern LIKE '%对比%');
