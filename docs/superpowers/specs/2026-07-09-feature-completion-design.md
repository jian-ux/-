# FeiSheng Bot — 功能补齐设计文档

## 概述

基于现有 feisheng-bot 项目的存量表和已完成模块，补齐已建表但未实现的业务逻辑、管理后台前端未对接的功能，以及引入渠道消息可靠性机制。

## 阶段一：Admin 后台补齐 + 核心业务逻辑

### 1.1 Intent 意图识别

**表**: `bot_intent` (已存在)
- `intent_name` — 意图名称
- `intent_keywords` — 触发的关键词（逗号分隔）
- `reply_template` — 命中后的回复模板
- `status` — 启用/禁用

**新增代码**:
- `com.feisheng.bot.admin.controller.IntentController` — 完整的 CRUD + 启用/禁用切换
- `com.feisheng.bot.admin.service.impl.IntentServiceImpl` — 核心匹配逻辑
- 匹配策略：用户输入包含任意关键词即触发，返回对应回复模板

**前端页面**: `/intent`
- 表格列表（意图名称、关键词预览、状态、操作按钮）
- 新建/编辑弹窗
- 启用/禁用切换

### 1.2 Reply Strategy 回复策略

**表**: `bot_reply_strategy` (已存在)
- `strategy_name` — 策略名称
- `priority` — 优先级（数字小的优先匹配）
- `rule_condition` — 规则条件（关键词/渠道类型等 JSON）
- `action` — 动作：BLOCK / FAQ_ONLY / AI_FALLBACK / HANDOFF
- `status` — 启用/禁用

**新增代码**:
- `ReplyStrategyController` — CRUD + 优先级排序
- `ReplyStrategyService` — 条件判断引擎

**前端页面**: `/settings/reply-strategy`
- 表格，按优先级排序
- 新建/编辑表单（策略名、条件 JSON、动作选择、优先级）
- 拖拽调整优先级（可选）

### 1.3 对话标签系统

**表**: `bot_conversation_tag` (已存在)

**新增代码**:
- 在 `ConversationAdminController` 中追加 `addTag` / `removeTag` / `getTags` 端点
- 前端在对话详情页展示标签 chips + 可添加/删除

### 1.4 日维统计定时任务

**表**: `bot_daily_statistics` (已存在)

**新增代码**:
- 在 admin 模块 `com.feisheng.bot.admin.task.StatisticsScheduledTask`
- `@Scheduled(cron = "0 5 0 * * ?")` 每天凌晨统计前一日数据
- 如果当天已有记录则更新、否则插入
- 统计口径：bot_conversation 按状态分、bot_message 按 role 分、bot_ai_reply_log 按 purpose 和 call_status 分

### 1.5 前端功能补齐

**CSAT 评分**:
- 后端 API 已有 `ConversationAdminController.updateCsat()`
- 对话详情页加「满意度」区域：1-5 星评分 + 文字反馈输入框
- 提交时调 `PUT /api/admin/conversation/{id}/csat`

**SLA 展示**:
- 后端已有 `priority` 和 `slaDeadline` 字段
- 对话列表加「优先级」和「SLA 截止时间」列
- 超时（超过截止时间）的行红色高亮

**角色权限管理**:
- 后端已有 `SysRole` / `SysPermission` / `SysUserRole` / `SysRolePermission` 表和 Mapper
- 新建 `/system/role` 页面：角色列表 + 权限树 + 用户分配
- 新建 `/system/permission` 页面：权限树管理
- 用户编辑页面添加角色选择器

**Chunk 审核 UI**:
- 后端已有 `POST /api/admin/doc/chunks/{chunkId}/approve` 和 `/reject`
- 上传文档详情页展示全部 chunk 列表（内容预览 + 状态标签 + 审核按钮）
- 只对 status=PROCESSING 的 chunk 显示审核操作

**路由与导航**:
- 补充导航菜单项：意图管理、回复策略、角色管理、权限管理
- 路由文件 `router/index.js` 追加对应项

## 阶段二：对话引擎集成

### Intent/Strategy 接入 DialogServiceImpl

- `DialogServiceImpl.send()` 在安全预检之后、FAQ 匹配之前插入 `IntentService.match(text)`：
  - 命中 intent → 直接返回 reply_template，记录回复日志
  - 继续走原有 FAQ/AI 链路
- `ReplyStrategyService.evaluate(context)` 在拿到回复之后做策略决策：
  - BLOCK → 替换回复为固定拒绝文案
  - FAQ_ONLY → 如果源头是 AI 生成则拦截
  - HANDOFF → 触发转接
  - AI_FALLBACK → 即使 FAQ 也不直接返回，改走 AI

## 阶段三：可靠性升级

### Maven 依赖
- `spring-retry` — 重试注解支持
- `resilience4j-spring-boot3` — 熔断器

### Embedding 重试
- `EmbeddingService.embedBatch()` 加 `@Retryable(maxAttempts=3, backoff=@Backoff(delay=1000))`

### 渠道消息重试
- `ChannelServiceImpl.processMessage()` 调 core 失败时重试 2 次

### Core 模块熔断
- `DialogServiceImpl` 调用 AI Provider 和 Knowledge 模块时配置 `@CircuitBreaker`
- 熔断降级：返回统一提示文案 + 日志记录

## 不变更的现有代码

- 不修改现有数据库表结构
- 不重构现有实体的字段映射
- 不改变 DialogServiceImpl 主流程的返回值结构
- docker-compose.yml 和 Dockerfile 维持现有形式

## 测试策略

- Intent/ReplyStrategy Service 层单独写单元测试
- 前端新页面通过 Playwright screenshot 验证
- 日维定时任务通过手动触发端点验证
