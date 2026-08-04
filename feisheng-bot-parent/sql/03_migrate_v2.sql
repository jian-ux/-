-- =============================================================================
-- feisheng-bot v2 迁移脚本
-- 新增: LLM 日志增强 + 禁答规则模块
-- =============================================================================

SET NAMES utf8mb4;

USE feisheng_bot_db;

-- 1. bot_ai_reply_log 扩展字段
ALTER TABLE bot_ai_reply_log
  ADD COLUMN provider_code VARCHAR(50) AFTER model_name,
  ADD COLUMN cost_cents INT DEFAULT 0 AFTER tokens_output,
  ADD COLUMN purpose VARCHAR(20) DEFAULT 'CHAT' AFTER cost_cents,
  ADD COLUMN call_status VARCHAR(20) DEFAULT 'SUCCESS' AFTER purpose,
  ADD COLUMN trace_json TEXT AFTER call_status,
  ADD COLUMN cited_chunk_ids VARCHAR(1000) AFTER trace_json;

-- 3. 禁答规则表
CREATE TABLE IF NOT EXISTS bot_forbidden_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_type VARCHAR(30) NOT NULL COMMENT 'SENSITIVE_WORD/FORBIDDEN_TOPIC/FORCE_HANDOFF/AI_DISCLAIMER',
  pattern TEXT NOT NULL COMMENT '匹配模式(普通文本或正则)',
  is_regex TINYINT DEFAULT 0 COMMENT '是否正则表达式',
  action VARCHAR(20) NOT NULL COMMENT 'BLOCK/REPLY_FIXED/HANDOFF/LOG_ONLY',
  reply_text TEXT COMMENT 'action=REPLY_FIXED时的固定回复',
  description VARCHAR(500) COMMENT '规则说明',
  is_enabled TINYINT DEFAULT 1,
  priority INT DEFAULT 0 COMMENT '优先级(数字小的先匹配)',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  INDEX idx_type_enabled(rule_type, is_enabled),
  INDEX idx_priority(priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 种子数据 — 禁答规则
INSERT INTO bot_forbidden_rule (rule_type, pattern, is_regex, action, reply_text, description, priority) VALUES
('FORCE_HANDOFF', '退款', 0, 'HANDOFF', NULL, '客户提退款 → 强制转人工', 1),
('FORCE_HANDOFF', '投诉', 0, 'HANDOFF', NULL, '客户投诉 → 强制转人工', 1),
('FORCE_HANDOFF', '曝光', 0, 'HANDOFF', NULL, '客户威胁曝光 → 强制转人工', 1),
('FORCE_HANDOFF', '律师函', 0, 'HANDOFF', NULL, '法律相关 → 强制转人工', 1),
('FORCE_HANDOFF', '起诉', 0, 'HANDOFF', NULL, '法律纠纷 → 强制转人工', 1),
('SENSITIVE_WORD', '傻逼|操你|妈的|fuck|shit', 0, 'BLOCK', NULL, '辱骂词汇 → 拒答', 10),
('FORBIDDEN_TOPIC', '哪家.*好|哪个.*好|竞品|对比', 1, 'REPLY_FIXED', '很抱歉，我们不评价其他服务商。如果您有具体需求，我可以帮您了解我们的产品优势。', '竞品评价 → 固定回复', 20),
('AI_DISCLAIMER', '保证胜诉|包赢|保证打赢|100%胜诉', 0, 'BLOCK', 'AI 不能对法律结果做任何承诺', '法律承诺 → 拒答', 30),
('AI_DISCLAIMER', '永久免费|永久会员|终身免费', 0, 'REPLY_FIXED', '关于权益和套餐的具体信息，建议您联系人工客服确认，我可以帮您转接。', 'AI 不能承诺永久权益', 30),
('AI_DISCLAIMER', '一定退款|保证退款|肯定能退', 0, 'REPLY_FIXED', '退款问题涉及您的具体账户情况，建议您联系人工客服处理，我可以帮您转接。', 'AI 不能保证退款结果', 30);

-- 4. embedding 列（RAG向量检索）
ALTER TABLE bot_knowledge_item ADD COLUMN embedding TEXT AFTER hit_count;
ALTER TABLE bot_knowledge_chunk ADD COLUMN embedding TEXT AFTER content;
ALTER TABLE bot_knowledge_chunk ADD COLUMN status VARCHAR(20) DEFAULT 'PENDING' AFTER embedding;
ALTER TABLE bot_conversation ADD COLUMN priority VARCHAR(10) DEFAULT 'P2' AFTER status;
ALTER TABLE bot_conversation ADD COLUMN sla_deadline DATETIME AFTER priority;

-- 5. CSAT满意度 + 未命中问题
ALTER TABLE bot_conversation ADD COLUMN csat_score INT AFTER sla_deadline;
ALTER TABLE bot_conversation ADD COLUMN csat_feedback VARCHAR(500) AFTER csat_score;

CREATE TABLE IF NOT EXISTS bot_unmatched_question (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  question TEXT NOT NULL,
  similar_count INT DEFAULT 1,
  is_resolved TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resolved(is_resolved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
