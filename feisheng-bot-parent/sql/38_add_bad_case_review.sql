SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_unmatched_question
  ADD COLUMN last_answer_decision VARCHAR(30) NULL AFTER last_answer_status,
  ADD COLUMN last_reason_code VARCHAR(100) NULL AFTER last_answer_decision,
  ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER last_csat_score,
  ADD COLUMN review_decision VARCHAR(30) NULL AFTER review_status,
  ADD COLUMN review_correct TINYINT NULL AFTER review_decision,
  ADD COLUMN review_category VARCHAR(40) NULL AFTER review_correct,
  ADD COLUMN review_note VARCHAR(1000) NULL AFTER review_category,
  ADD COLUMN reviewed_by BIGINT NULL AFTER review_note,
  ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
  ADD INDEX idx_bad_case_review_status(review_status),
  ADD INDEX idx_bad_case_review_correct(review_correct);

UPDATE bot_unmatched_question
SET review_status = 'PENDING'
WHERE review_status IS NULL OR review_status = '';
