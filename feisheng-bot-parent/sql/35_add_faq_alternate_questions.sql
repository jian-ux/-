SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_knowledge_item
  ADD COLUMN alternate_questions TEXT NULL AFTER keywords;

UPDATE bot_knowledge_item item
JOIN bot_faq_draft draft ON draft.published_item_id = item.id
SET item.alternate_questions = (
  SELECT COALESCE(JSON_ARRAYAGG(alias_question), JSON_ARRAY())
  FROM JSON_TABLE(
    draft.similar_questions_json,
    '$[*]' COLUMNS(alias_question VARCHAR(500) PATH '$')
  ) questions
  WHERE TRIM(alias_question) <> TRIM(item.question)
)
WHERE JSON_VALID(draft.similar_questions_json)
  AND (item.alternate_questions IS NULL OR item.alternate_questions = '');
