SET NAMES utf8mb4;
USE feisheng_bot_db;

CREATE TABLE IF NOT EXISTS bot_business_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  channel_type VARCHAR(20) NOT NULL,
  channel_user_id VARCHAR(100) NOT NULL,
  status VARCHAR(50),
  payment_status VARCHAR(50),
  item_summary VARCHAR(500),
  amount_cents BIGINT,
  currency VARCHAR(10) DEFAULT 'CNY',
  order_time DATETIME,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_order_no(order_no),
  INDEX idx_channel_owner(channel_type, channel_user_id),
  INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_business_logistics (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  carrier VARCHAR(100),
  tracking_no VARCHAR(100),
  status VARCHAR(50),
  latest_event VARCHAR(500),
  latest_event_time DATETIME,
  estimated_delivery_time DATETIME,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_logistics_order(order_no),
  INDEX idx_tracking_no(tracking_no),
  INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bot_tool_execution_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT,
  request_id VARCHAR(80) NOT NULL,
  tool_name VARCHAR(100) NOT NULL,
  provider_code VARCHAR(50),
  status VARCHAR(30) NOT NULL,
  input_json TEXT,
  output_summary VARCHAR(500),
  latency_ms INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_request(request_id),
  INDEX idx_tool_time(tool_name, create_time),
  INDEX idx_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO bot_business_order (
  order_no, channel_type, channel_user_id, status, payment_status,
  item_summary, amount_cents, currency, order_time
) VALUES (
  'FS202607170001', 'playground', 'admin-preview', '已发货', '已支付',
  '电子合同专业版年度套餐', 19900, 'CNY', '2026-07-17 09:30:00'
) ON DUPLICATE KEY UPDATE order_no = VALUES(order_no);

INSERT INTO bot_business_logistics (
  order_no, carrier, tracking_no, status, latest_event,
  latest_event_time, estimated_delivery_time
) VALUES (
  'FS202607170001', '顺丰速运', 'SF202607170001', '运输中',
  '快件已到达海口转运中心', '2026-07-17 16:20:00', '2026-07-18 18:00:00'
) ON DUPLICATE KEY UPDATE order_no = VALUES(order_no);
