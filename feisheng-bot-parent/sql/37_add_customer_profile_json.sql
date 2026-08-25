SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_customer
  ADD COLUMN profile_json TEXT NULL AFTER remark,
  ADD COLUMN profile_updated_at DATETIME NULL AFTER profile_json;
