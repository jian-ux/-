-- Keep the source cluster when a review operator merges it into another one.
SET @cluster_merge_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'bot_question_cluster'
    AND column_name = 'merged_into_id'
);
SET @cluster_merge_column_sql = IF(
  @cluster_merge_column_exists = 0,
  'ALTER TABLE bot_question_cluster ADD COLUMN merged_into_id BIGINT NULL AFTER ignored',
  'SELECT 1'
);
PREPARE cluster_merge_column_stmt FROM @cluster_merge_column_sql;
EXECUTE cluster_merge_column_stmt;
DEALLOCATE PREPARE cluster_merge_column_stmt;

SET @cluster_merge_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'bot_question_cluster'
    AND index_name = 'idx_cluster_merged_into'
);
SET @cluster_merge_index_sql = IF(
  @cluster_merge_index_exists = 0,
  'CREATE INDEX idx_cluster_merged_into ON bot_question_cluster(merged_into_id)',
  'SELECT 1'
);
PREPARE cluster_merge_index_stmt FROM @cluster_merge_index_sql;
EXECUTE cluster_merge_index_stmt;
DEALLOCATE PREPARE cluster_merge_index_stmt;
