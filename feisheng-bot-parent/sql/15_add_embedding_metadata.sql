SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_knowledge_item
    ADD COLUMN embedding_model VARCHAR(200) NULL AFTER embedding,
    ADD COLUMN embedding_version VARCHAR(64) NULL AFTER embedding_model,
    ADD COLUMN embedding_dimensions INT NULL AFTER embedding_version,
    ADD COLUMN embedding_content_hash VARCHAR(64) NULL AFTER embedding_dimensions;

ALTER TABLE bot_knowledge_chunk
    ADD COLUMN embedding_model VARCHAR(200) NULL AFTER embedding,
    ADD COLUMN embedding_version VARCHAR(64) NULL AFTER embedding_model,
    ADD COLUMN embedding_dimensions INT NULL AFTER embedding_version,
    ADD COLUMN embedding_content_hash VARCHAR(64) NULL AFTER embedding_dimensions;

CREATE INDEX idx_knowledge_item_embedding_version
    ON bot_knowledge_item (embedding_version);
CREATE INDEX idx_knowledge_chunk_embedding_version
    ON bot_knowledge_chunk (embedding_version);
