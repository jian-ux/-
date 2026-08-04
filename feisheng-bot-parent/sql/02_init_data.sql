SET NAMES utf8mb4;
USE feisheng_bot_db;
INSERT INTO sys_role (role_name, role_key) VALUES ('Super Admin', 'admin'), ('Customer Service', 'cs'), ('Operator', 'operator');
INSERT INTO sys_permission (parent_id, name, permission, path, type, icon, sort) VALUES
(0,'System','system:manage','/system',0,'Setting',1),(1,'User Management','system:user:list','/system/user',0,'User',1),
(1,'Role Management','system:role:list','/system/role',0,'Avatar',2),(0,'Knowledge Base','knowledge:manage','/knowledge',0,'Notebook',2),
(4,'FAQ Items','knowledge:faq:list','/knowledge/faq',0,'Reading',1),(0,'Conversation','conversation:manage','/conversation',0,'ChatDotSquare',3),
(0,'Channel Config','channel:manage','/channel',0,'Connection',4),(0,'AI Config','ai:manage','/ai',0,'Cpu',5),
(0,'Statistics','statistics:manage','/statistics',0,'DataAnalysis',6),(0,'Operation Log','log:manage','/log',0,'Document',7),
(0,'Tickets','ticket:manage','/ticket',0,'Ticket',8);
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.role_key = 'admin';
INSERT INTO bot_knowledge_category (parent_id, name, sort) VALUES (0,'Account',1),(0,'Billing',2),(0,'Technical Support',3);
INSERT INTO bot_knowledge_item (category_id, question, answer, keywords) VALUES
(1,'How to reset my password?','Click Forgot Password on login page. A reset link will be sent.','password,reset'),
(1,'How to update my profile?','Go to Settings > Profile to update.','profile,update'),
(2,'How to view my bills?','View bills in Billing section.','bill,payment'),
(3,'How to integrate the API?','See docs.example.com for API docs.','api,docs'),
(3,'System requirements?','Modern browsers, stable internet.','requirements,browser'),
(1,'如何重置密码？','点击登录页面的"忘记密码"，系统会发送重置链接到您的注册邮箱。如果您无法收到邮件，请联系人工客服。','密码,重置,忘记,找回'),
(1,'怎么修改个人信息？','登录后进入"设置 > 个人资料"，可以修改昵称、邮箱、手机号。实名认证信息修改需人工审核。','修改,个人资料,昵称,手机'),
(2,'如何查看消费账单？','在"财务中心 > 账单管理"中可查看所有消费明细。支持按月份筛选和导出 PDF 账单。','账单,消费,财务,查看'),
(2,'怎么申请发票？','在"财务中心 > 发票管理"中提交开票申请。电子发票 1-3 个工作日开具，纸质发票 5-7 个工作日邮寄。','发票,开票,申请'),
(2,'套餐到期怎么续费？','套餐到期前 7 天系统会发送续费提醒。可在"套餐中心"选择续费方案，支持月付和年付。','续费,到期,套餐,订阅'),
(3,'API 怎么接入？','请访问 docs.example.com 查看完整 API 文档。支持 RESTful 接口，提供 Java/Python/Node.js SDK。','API,接入,接口,对接,开发文档'),
(3,'系统支持哪些浏览器？','推荐使用 Chrome 90+、Edge 90+、Firefox 88+、Safari 14+。不支持 IE 浏览器。','浏览器,兼容,Chrome,系统要求'),
(3,'数据安全怎么保障？','所有数据传输使用 HTTPS 加密，存储采用 AES-256 加密。已通过等保三级认证和 ISO 27001 认证。','安全,加密,数据保护,认证');
INSERT INTO bot_ai_model_config (model_name, provider, api_url, api_key, model_type, status) VALUES ('GPT-4o-mini', 'openai', 'https://api.openai.com/v1/chat/completions', 'YOUR_API_KEY_HERE', 'LLM', 0);
