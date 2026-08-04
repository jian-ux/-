SET NAMES utf8mb4;
USE feisheng_bot_db;

-- Reuse the existing Zhipu account credential without exposing it in migrations.
-- The INSERT is idempotent and leaves the default chat model unchanged.
INSERT INTO bot_ai_model_config
    (model_name, provider, api_url, api_key, model_type, parameters, status, is_default)
SELECT
    'embedding-3',
    'zhipu',
    'https://open.bigmodel.cn/api/paas/v4/embeddings',
    source.api_key,
    'Embedding',
    '{"purpose":"knowledge_embedding"}',
    1,
    0
FROM bot_ai_model_config source
WHERE source.provider = 'zhipu'
  AND source.status = 1
  AND source.deleted = 0
  AND source.api_key IS NOT NULL
  AND source.api_key <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM bot_ai_model_config existing
      WHERE existing.provider = 'zhipu'
        AND existing.model_name = 'embedding-3'
        AND existing.model_type = 'Embedding'
        AND existing.deleted = 0
  )
ORDER BY source.is_default DESC, source.id ASC
LIMIT 1;
