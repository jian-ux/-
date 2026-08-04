-- Provide an atomic product-advantage answer and keep comparison replies useful
-- without making unsupported claims about competitors.
INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count
)
SELECT
    0,
    '点签有哪些产品优势？',
    '点签的优势主要体现在五个方面：1. 全流程电子签约，覆盖合同发起、身份认证、在线签署、签章管理、存证和归档；2. 安全合规，采用数字签名、数据加密和多重身份核验，保障合同真实性、完整性和法律效力；3. 多端使用与系统集成，支持微信、PC、钉钉、企业微信等渠道，并可通过 OpenAPI 接入现有 OA、ERP 等系统；4. 交付方式灵活，支持 SaaS、OpenAPI 和定制化开发，适配不同企业流程；5. 提供一对一服务，并可提供海南本地化支持。具体选型可以结合签署量、使用渠道和是否需要系统对接来判断。',
    '点签优势,产品优势,产品的优势,平台优势,产品特点,平台特点,为什么选择点签,点签有什么特点',
    1,
    0
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_knowledge_item
    WHERE question = '点签有哪些产品优势？'
      AND deleted = 0
);

UPDATE bot_knowledge_item
SET answer = '选择电子合同平台时，可以重点比较安全合规、业务适配、系统集成和服务响应。点签的特点包括：覆盖合同发起、身份认证、在线签署、签章管理和文件存证；支持微信、PC、钉钉、企业微信等多端使用，并可通过 OpenAPI 对接现有系统；同时支持 SaaS、OpenAPI 和定制化开发，并提供一对一服务。我们不对其他平台作主观优劣评价，具体可以根据签署量、使用渠道和系统对接需求判断是否适合。',
    keywords = '其他平台相比,和其他平台比较,平台对比,竞品比较,竞品对比,有什么特点,哪个好,区别,上上签,e签宝,法大大,契约锁',
    update_time = CURRENT_TIMESTAMP
WHERE question = '你们和其他电子合同平台相比哪个好？'
  AND deleted = 0;
