SET NAMES utf8mb4;
USE feisheng_bot_db;

INSERT INTO bot_knowledge_item
    (category_id, question, answer, keywords, status)
SELECT
    0,
    'e签宝说你们是小平台',
    '您有这个顾虑很正常。平台规模只是选型因素之一，更重要的是是否匹配实际业务、签署流程是否合规、系统是否稳定，以及后续服务能否及时响应。点签聚焦企业电子合同和电子签章，支持实名认证、合同发起、在线签署盖章、存证和合同管理，也支持微信、钉钉、企业微信及 API 接入。您这边主要是用于人事合同吗？我可以按签署人数、频次和是否需要对接人事系统，帮您判断是否适合。',
    'e签宝,小平台,平台规模,竞品,对比',
    1
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_knowledge_item
    WHERE question = 'e签宝说你们是小平台'
      AND deleted = 0
);
