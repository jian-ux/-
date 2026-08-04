CREATE TABLE IF NOT EXISTS bot_knowledge_item_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding TEXT,
    embedding_model VARCHAR(200),
    embedding_version VARCHAR(64),
    embedding_dimensions INT,
    embedding_content_hash VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_item_chunk (item_id, chunk_index),
    INDEX idx_item_id (item_id),
    INDEX idx_embedding_version (embedding_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
