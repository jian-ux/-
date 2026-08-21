-- Persisted, review-only snapshots for unmatched-question clustering.
CREATE TABLE IF NOT EXISTS bot_question_cluster_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  include_resolved TINYINT NOT NULL DEFAULT 0,
  question_count INT NOT NULL DEFAULT 0,
  cluster_count INT NOT NULL DEFAULT 0,
  noise_count INT NOT NULL DEFAULT 0,
  threshold DECIMAL(6,5) NOT NULL DEFAULT 0.82000,
  embedding_used TINYINT NOT NULL DEFAULT 0,
  embedding_model VARCHAR(200),
  embedding_version VARCHAR(100),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  INDEX idx_cluster_run_time(create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_question_cluster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  cluster_number INT NOT NULL,
  title VARCHAR(500) NOT NULL,
  question_count INT NOT NULL DEFAULT 0,
  total_occurrences INT NOT NULL DEFAULT 0,
  cohesion DECIMAL(8,6) NOT NULL DEFAULT 0,
  ignored TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  INDEX idx_cluster_run(run_id),
  INDEX idx_cluster_review(run_id, ignored)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_question_cluster_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cluster_id BIGINT NOT NULL,
  unmatched_question_id BIGINT,
  question VARCHAR(2000) NOT NULL,
  analysis_question VARCHAR(2000),
  similar_count INT NOT NULL DEFAULT 1,
  similarity_to_title DECIMAL(8,6) NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  INDEX idx_cluster_item_cluster(cluster_id),
  INDEX idx_cluster_item_source(unmatched_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
