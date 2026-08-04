-- Restore the reviewed usage-channel FAQ and return it verbatim on exact matches.
SET NAMES utf8mb4;
USE feisheng_bot_db;

SET @usage_question = '点签可以在哪里使用？';
SET @usage_answer = '应用可以通过微信、pc网页端、钉钉进入使用。\n1、微信端：搜索公众号或小程序 —— 点签电子合同\n2、PC网页端：https://ding.fs-signature.com/pc/\n3、钉钉广场搜索：点签电子合同应用开通使用';
SET @usage_keywords = '点签在哪里使用,点签使用入口,点签使用渠道,微信端,PC网页端,钉钉应用';

INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count,
    direct_answer_enabled, embedding, embedding_model, embedding_version,
    embedding_dimensions, embedding_content_hash, deleted
)
SELECT 0, @usage_question, @usage_answer, @usage_keywords, 1, 0,
       1, NULL, NULL, NULL, NULL, NULL, 0
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_knowledge_item
    WHERE question IN (@usage_question, '点签可以在哪里使用')
);

SET @usage_item_id = (
    SELECT MIN(id)
    FROM bot_knowledge_item
    WHERE question IN (@usage_question, '点签可以在哪里使用')
);

UPDATE bot_knowledge_item
SET question = @usage_question,
    answer = @usage_answer,
    keywords = @usage_keywords,
    status = 1,
    deleted = 0,
    direct_answer_enabled = 1,
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedding_dimensions = NULL,
    embedding_content_hash = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE id = @usage_item_id;

UPDATE bot_knowledge_item
SET status = 0,
    deleted = 1,
    direct_answer_enabled = 0,
    update_time = CURRENT_TIMESTAMP
WHERE question IN (@usage_question, '点签可以在哪里使用')
  AND id <> @usage_item_id;

DELETE FROM bot_knowledge_item_chunk
WHERE item_id = @usage_item_id;
