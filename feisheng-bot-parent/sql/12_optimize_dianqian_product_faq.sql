-- Keep the product-definition FAQ atomic so direct answers do not expand into
-- unrelated company or industry introductions.
INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count
)
SELECT
    0,
    '点签是什么？',
    '点签是一款面向企业的电子合同与电子签章产品，主要用于在线完成合同签署、身份认证、签章管理和文件存证，在提高签署效率的同时，保障电子文件的真实性、完整性和法律效力。',
    '点签,点签是什么,点签做什么,点签产品介绍',
    1,
    0
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_knowledge_item
    WHERE question IN ('点签是什么？', '点签是什么?')
      AND deleted = 0
);

UPDATE bot_knowledge_item
SET answer = '点签是一款面向企业的电子合同与电子签章产品，主要用于在线完成合同签署、身份认证、签章管理和文件存证，在提高签署效率的同时，保障电子文件的真实性、完整性和法律效力。',
    keywords = '点签,点签是什么,点签做什么,点签产品介绍',
    status = 1,
    update_time = CURRENT_TIMESTAMP
WHERE question IN ('点签是什么？', '点签是什么?')
  AND deleted = 0;
