# RAG 第三阶段：图片 OCR 与截图问答

## 交付范围

第三阶段提供两条图片链路：

- **图片知识库**：图片上传至 MinIO，Tesseract OCR 提取文字，随后复用文档分块、Embedding、人工审核和 RAG 检索。
- **截图问答**：试聊中上传截图，OCR 完成后围绕该截图提问；截图上下文不进入全局索引，并在 24 小时后自动清理。

两条链路均返回 `sourceType=image` 的结构化引用，包含图片 ID、标题、OCR 片段和鉴权预览地址。

## 运行依赖

Docker 应用镜像已安装：

- `tesseract-ocr`
- `tesseract-ocr-chi-sim`
- `tesseract-ocr-eng`

默认 OCR 配置：

```dotenv
OCR_TESSERACT_COMMAND=tesseract
OCR_LANGUAGES=chi_sim+eng
OCR_TIMEOUT_SECONDS=60
OCR_IMAGE_MAX_BYTES=10485760
OCR_IMAGE_MAX_PIXELS=40000000
OCR_IMAGE_MAX_DIMENSION=3200
OCR_CHAT_RETENTION_HOURS=24
```

支持格式：`png`、`jpg`、`jpeg`、`bmp`、`tif`、`tiff`。

## 数据库迁移

已有 MySQL 数据卷必须执行：

```powershell
Get-Content -Raw -Encoding utf8 feisheng-bot-parent/sql/07_add_image_ocr.sql |
  docker exec -i -e MYSQL_PWD=<MYSQL_PASSWORD> feisheng-mysql mysql -uroot
```

应用启动时会校验 `bot_knowledge_document` 的 OCR 字段；未迁移会明确提示执行 `07_add_image_ocr.sql`。

## 图片知识库

上传接口沿用：

```text
POST /api/admin/doc/upload
Content-Type: multipart/form-data
file=<image>
```

处理状态：

1. 文档状态设为处理中，`ocrStatus=PROCESSING`。
2. 校验扩展名、文件大小、真实图片内容与像素数。
3. 图片缩放、白底合成、灰度化后执行 Tesseract。
4. OCR 文本写入文档记录，并生成分块与向量。
5. 分块默认为 `PENDING`，人工审核为 `APPROVED` 后进入索引。

辅助接口：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/admin/doc/ocr/status` | 查看 Tesseract 版本、语言与可用状态 |
| `POST` | `/api/admin/doc/{id}/ocr/retry` | 重试失败的图片 OCR |
| `GET` | `/api/admin/doc/{id}/preview` | 鉴权预览图片或文档 |

## 截图问答

先上传截图：

```text
POST /api/admin/playground/image
Content-Type: multipart/form-data
file=<image>
```

成功响应包含 `id`、`ocrText`、`ocrChars`、图片尺寸、处理耗时及过期时间。

然后提问：

```json
{
  "text": "这笔订单支付了吗，金额是多少？",
  "imageId": 123,
  "sessionId": "admin-preview",
  "modelId": 1
}
```

聊天截图使用 `sourceScope=CHAT`，不会生成全局知识分块；每小时清理一次已过期图片及 MinIO 对象。

## 安全边界

- 只接受图片白名单扩展名，并通过 ImageIO 验证真实内容。
- 默认限制 10MB、4000 万像素和 3200 最大处理边长。
- OCR 子进程有超时，参数不经过 shell 拼接。
- OCR 文本被视为不可信资料，系统提示词明确禁止执行图片中的指令。
- OCR 原文在列表 JSON 中隐藏，只在授权截图上传响应与模型上下文中使用。
- 图片预览经过后台 JWT 鉴权，不返回公开永久 URL。

## 验收结果

真实 Docker 环境已验证：

- Tesseract `5.5.0`，`chi_sim+eng` 可用。
- 中文订单截图 OCR 用时约 `467ms`，准确识别订单号、支付状态和金额。
- 截图问答准确回答“已支付，实付 299.00 元”，引用类型为 `image`。
- 同一图片作为知识库内容时，可完成 OCR、向量化、审核、检索和图片引用。
