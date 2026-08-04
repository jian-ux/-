SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_conversation
  ADD COLUMN emotion_label VARCHAR(30) DEFAULT 'NEUTRAL' AFTER csat_feedback,
  ADD COLUMN emotion_score DECIMAL(6,4) DEFAULT 0 AFTER emotion_label,
  ADD COLUMN emotion_trend VARCHAR(30) DEFAULT 'STABLE' AFTER emotion_score,
  ADD COLUMN negative_streak INT DEFAULT 0 AFTER emotion_trend,
  ADD COLUMN emotion_risk VARCHAR(20) DEFAULT 'LOW' AFTER negative_streak,
  ADD INDEX idx_conversation_emotion(emotion_label, emotion_risk, update_time);
