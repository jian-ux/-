-- Enable direct output only for atomic, approved answers that are safe to return verbatim.
SET NAMES utf8mb4;
USE feisheng_bot_db;

UPDATE bot_knowledge_chunk chunk
JOIN bot_knowledge_document document ON document.id = chunk.document_id
SET chunk.direct_answer_enabled = 1
WHERE chunk.status = 'APPROVED'
  AND chunk.content_type = 'QA'
  AND document.publish_status = 'PUBLISHED'
  AND document.source_scope = 'KNOWLEDGE'
  AND chunk.qa_question IN (
      '电子合同具有法律效力吗？',
      '需要有法律效力的线上签署，你们是否能具备？'
  );
