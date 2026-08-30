INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT parent.id, '迁移任务查看', 'knowledge:migration:view', '/knowledge/migrations', 0, 'DocumentCopy', 10, 1
FROM sys_permission parent
WHERE parent.permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:migration:view');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT parent.id, '迁移冲突审核', 'knowledge:migration:review', '/knowledge/migrations', 0, 'Checked', 11, 1
FROM sys_permission parent
WHERE parent.permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:migration:review');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT parent.id, '迁移版本切换', 'knowledge:migration:switch', '/knowledge/migrations', 0, 'Switch', 12, 1
FROM sys_permission parent
WHERE parent.permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:migration:switch');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort, status)
SELECT parent.id, '迁移版本回滚', 'knowledge:migration:rollback', '/knowledge/migrations', 0, 'Refresh', 13, 1
FROM sys_permission parent
WHERE parent.permission='knowledge:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission='knowledge:migration:rollback');
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id FROM sys_role role CROSS JOIN sys_permission permission
WHERE role.role_key='admin' AND permission.permission IN
('knowledge:migration:view','knowledge:migration:review','knowledge:migration:switch','knowledge:migration:rollback');
