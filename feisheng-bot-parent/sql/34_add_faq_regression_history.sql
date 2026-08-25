-- Persist FAQ regression summaries so quality changes remain visible across page reloads.
CREATE TABLE IF NOT EXISTS bot_faq_regression_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  passed TINYINT NOT NULL,
  prompt_version VARCHAR(200),
  published_draft_count INT NOT NULL,
  dataset_case_count INT NOT NULL,
  executed_case_count INT NOT NULL,
  passed_case_count INT NOT NULL,
  failed_case_count INT NOT NULL,
  truncated TINYINT NOT NULL DEFAULT 0,
  failed_cases_json LONGTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  INDEX idx_faq_regression_created(create_time),
  INDEX idx_faq_regression_passed(passed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
