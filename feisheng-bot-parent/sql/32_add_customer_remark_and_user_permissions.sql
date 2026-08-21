SET NAMES utf8mb4;
USE feisheng_bot_db;

ALTER TABLE bot_customer
  ADD COLUMN remark VARCHAR(500) NULL AFTER email;

CREATE TABLE sys_user_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  UNIQUE KEY uk_user_permission(user_id, permission_id),
  INDEX idx_user_permission_user(user_id),
  INDEX idx_user_permission_permission(permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE sys_permission SET name='统计数据', permission='dashboard:view', path='/dashboard', sort=10
WHERE permission='statistics:manage';
UPDATE sys_permission SET name='渠道配置', permission='channel:view', path='/channel', sort=20
WHERE permission='channel:manage';
UPDATE sys_permission SET name='对话监控', permission='conversation:view', path='/conversation', sort=30
WHERE permission='conversation:manage';
UPDATE sys_permission SET name='智能模型', permission='ai:model:view', path='/ai/model', sort=70
WHERE permission='ai:manage';
UPDATE sys_permission SET name='工单管理', permission='ticket:view', path='/ticket', sort=80
WHERE permission='ticket:manage';
UPDATE sys_permission SET name='操作日志', permission='log:view', path='/log', sort=90
WHERE permission='log:manage';
UPDATE sys_permission SET name='系统管理', path='/system', sort=110 WHERE permission='system:manage';
UPDATE sys_permission SET name='用户管理', path='/system/user', sort=1 WHERE permission='system:user:list';
UPDATE sys_permission SET status=0 WHERE permission='system:role:list';
UPDATE sys_permission SET name='知识库管理', path='/knowledge', sort=100 WHERE permission='knowledge:manage';
UPDATE sys_permission SET name='常见问题管理', path='/knowledge/faq', sort=1 WHERE permission='knowledge:faq:list';

INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT 0, '智能试聊', 'playground:view', '/playground', 0, 'ChatLineSquare', 40, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='playground:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT 0, '客户管理', 'customer:view', '/customer', 0, 'UserFilled', 50, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='customer:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT 0, '意图管理', 'intent:view', '/intent', 0, 'SetUp', 60, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='intent:view');

INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '知识库上传', 'knowledge:upload:view', '/knowledge/upload', 0, 'Upload', 2, 1
FROM sys_permission WHERE permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:upload:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '结构化知识审核', 'knowledge:semantic:view', '/knowledge/semantic-units', 0, 'Checked', 3, 1
FROM sys_permission WHERE permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:semantic:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '知识质量审计', 'knowledge:quality:view', '/knowledge/quality-audit', 0, 'DocumentChecked', 4, 1
FROM sys_permission WHERE permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:quality:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '未命中问题', 'knowledge:unmatched:view', '/knowledge/unmatched', 0, 'QuestionFilled', 5, 1
FROM sys_permission WHERE permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:unmatched:view');

INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '权限分配', 'system:permission:assign', '/system/permission', 0, 'Key', 2, 1
FROM sys_permission WHERE permission='system:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='system:permission:assign');

INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT 0, '设置', 'settings:manage', '/settings', 0, 'Tools', 120, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='settings:manage');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '安全规则', 'settings:rules:view', '/settings/rules', 0, 'Lock', 1, 1
FROM sys_permission WHERE permission='settings:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='settings:rules:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT id, '回复策略', 'settings:reply-strategy:view', '/settings/reply-strategy', 0, 'ChatLineRound', 2, 1
FROM sys_permission WHERE permission='settings:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='settings:reply-strategy:view');

UPDATE sys_permission
SET parent_id=0, status=1, deleted=0
WHERE permission IN (
  'dashboard:view', 'channel:view', 'conversation:view', 'playground:view',
  'customer:view', 'intent:view', 'ai:model:view', 'ticket:view', 'log:view',
  'knowledge:manage', 'system:manage', 'settings:manage'
);
UPDATE sys_permission child
JOIN sys_permission parent ON parent.permission='knowledge:manage'
SET child.parent_id=parent.id, child.status=1, child.deleted=0
WHERE child.permission IN (
  'knowledge:faq:list', 'knowledge:upload:view', 'knowledge:semantic:view',
  'knowledge:quality:view', 'knowledge:unmatched:view'
);
UPDATE sys_permission child
JOIN sys_permission parent ON parent.permission='system:manage'
SET child.parent_id=parent.id, child.status=1, child.deleted=0
WHERE child.permission IN ('system:user:list', 'system:permission:assign');
UPDATE sys_permission child
JOIN sys_permission parent ON parent.permission='settings:manage'
SET child.parent_id=parent.id, child.status=1, child.deleted=0
WHERE child.permission IN ('settings:rules:view', 'settings:reply-strategy:view');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
CROSS JOIN sys_permission permission
WHERE role.role_key='admin' AND permission.status=1 AND permission.deleted=0;
