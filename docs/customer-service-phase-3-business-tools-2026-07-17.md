# 智能客服第三阶段：订单与物流查询工具

## 能力范围

- 对话安全预检通过后，优先识别订单和物流查询意图，再进入 RAG。
- 支持从当前消息或最近用户消息中补全订单号。
- 缺少订单号时追问，不调用模型、不猜测业务状态。
- 查询成功后使用结构化业务数据生成确定性回复。
- 未找到订单时提示用户检查订单号。
- 订单归属不一致、数据源不可用或调用失败时转人工。
- 工具审计仅记录掩码订单号、状态、耗时和请求 ID。

本阶段只执行只读查询，不包含修改地址、取消订单、退款或预约售后。

## 本地数据源

默认 `BUSINESS_API_ENABLED=false`，使用：

- `bot_business_order`
- `bot_business_logistics`
- `bot_tool_execution_log`

迁移文件为 `feisheng-bot-parent/sql/08_add_business_query_tools.sql`。

演示数据：

- 订单号：`FS202607170001`
- 所属渠道：`playground`
- 所属用户：`admin-preview`

只有完全匹配该渠道身份的会话才能查询演示订单。

管理接口：

- `GET /api/admin/business/order/list`
- `POST /api/admin/business/order/save`
- `GET /api/admin/business/logistics/list`
- `POST /api/admin/business/logistics/save`
- `GET /api/admin/business/tool-log/list`

以上接口要求管理员权限。

## 外部 HTTP 数据源

配置：

```properties
BUSINESS_API_ENABLED=true
BUSINESS_API_BASE_URL=https://business.example.com/api
BUSINESS_API_TOKEN=replace-with-service-token
BUSINESS_API_ORDER_PATH=/orders/{orderNo}
BUSINESS_API_LOGISTICS_PATH=/logistics/{orderNo}
BUSINESS_API_CONNECT_TIMEOUT_SECONDS=3
BUSINESS_API_READ_TIMEOUT_SECONDS=8
BUSINESS_API_REQUIRE_OWNER_MATCH=true
```

每个请求会发送：

```text
Authorization: Bearer <BUSINESS_API_TOKEN>
X-Channel-Type: web
X-Channel-User-Id: customer-123
X-Request-Id: <unique-request-id>
```

订单接口响应可以直接返回对象，也可以使用 `data` 包装：

```json
{
  "data": {
    "orderNo": "FS202607170001",
    "channelType": "web",
    "channelUserId": "customer-123",
    "status": "已发货",
    "paymentStatus": "已支付",
    "itemSummary": "电子合同专业版年度套餐",
    "amountCents": 19900,
    "currency": "CNY",
    "orderTime": "2026-07-17T09:30:00Z"
  }
}
```

启用归属校验时，`channelType` 和 `channelUserId` 必须与请求身份一致，否则拒绝返回订单数据并转人工。

物流接口响应：

```json
{
  "data": {
    "orderNo": "FS202607170001",
    "carrier": "顺丰速运",
    "trackingNo": "SF202607170001",
    "status": "运输中",
    "latestEvent": "快件已到达海口转运中心",
    "latestEventTime": "2026-07-17T16:20:00Z",
    "estimatedDeliveryTime": "2026-07-18T18:00:00Z"
  }
}
```

HTTP `404` 解释为未找到，`403` 解释为归属验证失败，其余连接错误会触发人工处理。

## 对话示例

```text
用户：帮我查订单状态
客服：请提供要查询的订单号，例如 FS202607170001。
用户：FS202607170001
客服：订单 FS202607170001 当前状态：已发货；支付状态：已支付……
```

```text
用户：订单号 FS202607170001 的物流到哪了？
客服：订单 FS202607170001 由顺丰速运承运……
```
