ALTER TABLE bot_knowledge_chunk
    ADD COLUMN section_path VARCHAR(1000) NULL AFTER content,
    ADD COLUMN char_count INT NULL AFTER section_path,
    ADD COLUMN chunk_strategy_version VARCHAR(32) NULL AFTER char_count;
