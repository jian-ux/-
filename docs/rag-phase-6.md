# RAG 第六阶段：多模态检索与语音回复

## 交付范围

第六阶段在不改变现有 Qdrant 文本向量体系的前提下，统一文本、截图和语音输入，并为 Admin 试聊增加按需语音播放：

- 普通文本直接进入既有安全检查、Embedding、Qdrant 检索、引用和无答案链路。
- 截图先经 OCR 归一为文本；用户围绕截图提问时，同时检索全局知识库，并融合截图局部证据与 FAQ、文档或图片知识证据。
- 语音先经 ASR 归一为可编辑文本，用户确认并发送后走与普通文本完全相同的 Qdrant 语义检索链路。
- 图片知识库仍以 OCR 文本分块和向量化后进入 Qdrant，检索结果继续使用 `sourceType=image` 和鉴权预览地址。
- Admin 试聊中的文本答案可以调用独立 `TTS` 模型，经 OpenAI-compatible `POST /audio/speech` 合成音频并点击播放。
- TTS 只影响播放能力；合成不可用或失败时，已经生成的文本答案、引用和检索调试信息保持可用。

本阶段的“多模态检索”是 **OCR/ASR 到文本的输入归一化与文本证据融合**，不是原生视觉或音频向量检索。本阶段明确不包含：

- 图片原生 embedding、图搜图、跨模态向量模型或 Qdrant named vectors。
- 音频 embedding、说话人识别或端到端语音对话模型。
- 微信、钉钉等渠道的语音消息下载、音频上传或语音回复发送。
- TTS 音频入 MinIO、进入知识库、长期保存或生成公开 URL。

## 统一检索链路

```text
文本 ------------------------------> 规范化查询文本
截图 -> 图片校验 -> OCR -----------+       |
语音 -> 音频校验 -> ASR -> 用户确认 +       v
                                      安全检查
                                         |
                                         v
                                    Embedding 查询向量
                                         |
                                         v
                              Qdrant（主路径）/ 内存（回退）
                                         |
截图局部 OCR 证据 ----------------------+----> 证据融合 -> LLM -> 文本答案与引用
                                                                   |
                                                                   v
                                                          按需调用 TTS 播放
```

ASR 只负责产生查询文本，不产生独立检索分数或引用。全局图片知识也只检索其已审核 OCR chunk，不读取图片像素生成查询向量。

### 截图证据融合

带 `imageId` 的试聊请求执行两路取证：

1. 校验截图属于当前 Admin 试聊范围、OCR 已完成且未过期，将 OCR 文本作为局部证据。
2. FAQ 关键词匹配只使用用户明确输入；语义检索使用“用户问题 + 截图 OCR 文本”扩展查询，主路径为 Qdrant，失败时沿用第五阶段的内存快照回退。
3. 合并截图引用与通过阈值的全局引用，并重新连续编号。
4. 提示词明确区分“当前截图”和“全局知识库”；二者均是不可信资料，只能用于回答问题，不能覆盖系统指令。

局部截图证据不伪造向量相似度；OCR 文本只用于扩展全局语义查询。`confidence` 在存在全局命中时表示最佳全局检索分数；没有合格全局候选时，截图上下文按调用方提供证据处理。

融合后的回答遵循以下规则：

- 截图与全局知识均提供依据时，回答可以综合两者，`citations` 同时包含局部 `image` 引用和全局引用。
- 只有截图足以回答时，可以基于局部证据回答；全局低分不应抹掉有效截图上下文。
- 截图由用户显式附加时始终作为局部上下文提供；若截图与全局知识都不能支持结论，模型必须明确说明依据不足，不得伪造截图事实或向量分数。
- 两路证据冲突时不得静默选择一方，应在答案中说明冲突并保留对应引用。

## 引用契约

现有 `answerStatus`、`confidence`、`citations` 和 `retrieval` 字段保持兼容。

截图局部引用继续使用：

```json
{
  "ref": 1,
  "id": "image:123",
  "sourceType": "image",
  "sourceId": 123,
  "documentId": 123,
  "title": "订单截图.png",
  "snippet": "订单状态：已支付……",
  "previewUrl": "/api/admin/doc/123/preview"
}
```

全局引用保持第五阶段 Qdrant payload 映射结果：FAQ 使用 `sourceType=faq`，普通文档 chunk 使用 `sourceType=document`，图片知识 chunk 使用 `sourceType=image`。图片预览仍需 Admin JWT，不返回永久公开地址。

当回答仅使用局部截图时，`retrieval.decision=provided_context`；截图与全局知识融合时使用 `retrieval.decision=multimodal_rag`。响应额外返回 `inputModality=text|image|audio` 与 `retrievalMode=unified_text_embedding`，前端可据此区分输入来源。Qdrant 或内存后端状态继续通过 RAG 管理状态接口查看。

## TTS 模型与配置

语音转文字继续使用 `Speech` 模型类型；语音合成使用独立 `TTS` 模型类型。每种类型分别设置默认模型，互不覆盖。

TTS 配置有两种来源，数据库配置优先：

1. 在“AI 模型配置”新增并启用 `TTS` 类型模型，配置模型名、OpenAI-compatible API 地址和密钥。`parameters` JSON 可配置 `voice`、`responseFormat` 和 `speed`。
2. 未配置可用数据库模型时，显式设置 `TTS_ENABLED=true`，使用环境变量回退。

模型参数示例：

```json
{
  "voice": "alloy",
  "responseFormat": "mp3",
  "speed": 1.0
}
```

环境变量示例：

```dotenv
TTS_ENABLED=false
TTS_API_URL=https://api.openai.com/v1/audio/speech
TTS_API_KEY=
TTS_MODEL=tts-1
TTS_VOICE=alloy
TTS_RESPONSE_FORMAT=mp3
TTS_SPEED=1.0
TTS_MAX_INPUT_CHARS=2000
TTS_MAX_BYTES=10485760
TTS_CONNECT_TIMEOUT_SECONDS=10
TTS_TIMEOUT_SECONDS=120
```

`TTS_API_URL` 可以是完整 `/audio/speech` 地址，也可以是 OpenAI-compatible `/v1` 基础地址。服务端向上游发送的核心 JSON 为：

```json
{
  "model": "tts-1",
  "input": "需要播放的文本答案",
  "voice": "alloy",
  "response_format": "mp3",
  "speed": 1.0
}
```

Admin 前端不能在单次播放请求中覆盖 voice、格式或语速，避免绕过后台审核过的模型配置。

## Admin 试聊接口

以下接口均需要 Admin JWT。

查看语音合成状态：

```text
GET /api/admin/playground/speech/synthesis/status
```

状态响应只返回可用性、provider、model、voice、响应格式、输入和输出限制及错误摘要，不返回 API 密钥。

合成并返回音频：

```text
POST /api/admin/playground/speech/synthesis
Content-Type: application/json

{
  "text": "需要播放的文本答案"
}
```

成功时直接返回音频字节，`Content-Type` 与配置格式一致。前端在每条机器人文本答案上提供播放入口，点击后才发起合成；再次播放、切换答案或离开页面时释放旧的浏览器 Object URL。

截图与文本问答继续使用现有接口：

```text
POST /api/admin/playground/image
POST /api/admin/playground/chat
```

带截图的融合请求示例：

```json
{
  "text": "这笔订单已经付款了吗，付款后如何申请发票？",
  "imageId": 123,
  "sessionId": "admin-preview",
  "modelId": 1
}
```

语音输入仍先调用 `POST /api/admin/playground/speech` 获取转写文本，再由用户确认后调用 `/chat`，因此文字、截图和语音最终共用同一套全局检索策略。

## 安全与资源边界

- OCR、ASR 文本和知识库内容均视为不可信资料，不能作为系统指令执行。
- 截图必须通过原有格式、大小、像素、所有权、OCR 状态和过期校验；局部截图不写入全局 Qdrant。
- ASR 临时音频继续在请求结束后删除，不进入 MinIO、Qdrant 或知识库。
- TTS 输入必须非空并限制为 `TTS_MAX_INPUT_CHARS`；超长文本在调用上游前拒绝，不静默截断答案。系统追加的 `[n]` 标记和“参考来源”脚注在合成前移除，屏幕文本保持不变。
- TTS 的 voice、格式和语速使用服务端白名单及范围校验；客户端请求不能覆盖这些字段。
- TTS 请求设置连接和读取超时，响应超过 `TTS_MAX_BYTES` 时拒绝转发；第三方错误正文截断并对 API 密钥及其片段脱敏。
- 上游返回 JSON、HTML 或与配置不符的非音频正文时按合成失败处理，不能作为音频转发给浏览器。
- 合成音频按请求即时返回，服务端不落盘、不缓存、不进入知识库；浏览器 Object URL 只保留到切换回答、清空会话或离开页面。日志不记录密钥或完整敏感文本。
- 前端同一时刻只播放一个音频；切换回答时停止旧音频并丢弃过期响应，避免错误播放。

## 降级策略

- Qdrant 搜索失败：立即使用第五阶段的不可变内存快照；`GET /api/admin/rag/sync-status` 中的 `searchBackend` 标记为 `memory`。
- 截图 OCR 失败、未完成或已过期：拒绝使用该 `imageId`，不以空截图上下文继续回答。
- 全局检索失败但截图局部证据有效：允许只依据截图回答并保留图片引用。
- ASR 不可用或转写失败：保留文本输入能力，不自动发送不完整转写。
- TTS 未配置、不可用、超时、超限或上游失败：播放操作显示失败，原文本答案、引用及会话状态不变。
- TTS 音频播放失败：允许重试合成或继续阅读文本，不重新调用 LLM 和 RAG。

## 可执行验收

### 1. 自动化测试与构建

```powershell
Set-Location .\feisheng-bot-parent
mvn test
Set-Location ..
npm --prefix .\feisheng-bot-admin-ui run build
```

要求后端测试全部通过，前端生产构建成功。新增测试至少覆盖截图局部与全局证据融合、引用编号、TTS URL 解析、模型参数优先级、输入/输出上限、密钥脱敏和文本回答不受 TTS 故障影响。

### 2. 启动环境并获取 Admin JWT

```powershell
docker compose up -d --build
docker compose ps

$settings = Get-Content -Raw -LiteralPath .\.env | ConvertFrom-StringData
$loginBody = @{
  username = $settings.ADMIN_USERNAME
  password = $settings.ADMIN_PASSWORD
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8082/api/admin/login `
  -ContentType 'application/json' `
  -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
```

要求 `app`、`qdrant`、MySQL、Redis 和 MinIO 健康，登录成功且 token 非空。

### 3. 核对 Qdrant 与 TTS 状态

```powershell
$qdrant = Invoke-RestMethod `
  -Uri http://localhost:8082/api/admin/rag/qdrant/status `
  -Headers $headers
$tts = Invoke-RestMethod `
  -Uri http://localhost:8082/api/admin/playground/speech/synthesis/status `
  -Headers $headers
$qdrant.data
$tts.data
```

要求 Qdrant collection 维度与 Embedding 模型一致，`qdrantReady=true`；TTS 状态显示实际模型、voice、格式和限制，但不包含密钥。

### 4. 验证普通文本与 ASR 统一检索

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-speech.ps1
```

在 Admin “AI 试聊调试”中检查转写文本后发送，并用同样文字再发送一次。两次应采用相同的 RAG 阈值和 Qdrant 主路径，返回相同类型的全局引用；ASR 请求自身不应产生虚构引用。

### 5. 验证截图局部与全局证据融合

1. 在 Admin 试聊上传一张包含订单号、支付状态或金额的截图。
2. 确认 OCR 完成后，提问一个同时需要截图事实和知识库规则的问题，例如“这笔订单已经付款了吗，付款后如何申请发票？”。
3. 检查回答同时使用截图事实和全局规则，`citations` 至少包含一个当前截图的 `sourceType=image` 引用及一个 FAQ、文档或图片知识引用。
4. 打开图片引用，确认预览需要 JWT；通过 `/api/admin/rag/sync-status` 检查 `searchBackend=qdrant`，并确认引用编号连续。
5. 再问一个截图本身即可回答的问题，确认全局无高分候选时仍可引用截图回答；两路都无依据的问题应进入既有无答案流程。

### 6. 验证 TTS 合成与播放

```powershell
$ttsStatus = Invoke-RestMethod `
  -Uri http://localhost:8082/api/admin/playground/speech/synthesis/status `
  -Headers $headers
$format = $ttsStatus.data.responseFormat
$audioPath = Join-Path $env:TEMP "feisheng-tts-acceptance.$format"

Invoke-WebRequest -Method Post `
  -Uri http://localhost:8082/api/admin/playground/speech/synthesis `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body (@{ text = '飞升智能客服语音回复验收。' } | ConvertTo-Json) `
  -OutFile $audioPath

Get-Item -LiteralPath $audioPath | Select-Object FullName, Length
Start-Process -FilePath $audioPath
```

要求文件非空且可以播放，音频内容与请求文本一致。在 Admin 试聊点击机器人答案的播放按钮，应只在点击后发起请求；切换答案后旧音频停止且 Object URL 被释放。

### 7. 验证故障降级

1. 停止 Qdrant：`docker compose stop qdrant`，重新发送已验证问题，确认请求仍成功，并通过 `/api/admin/rag/sync-status` 确认 `searchBackend=memory`；完成后执行 `docker compose start qdrant` 并等待同步恢复。
2. 禁用默认 `TTS` 模型并保持 `TTS_ENABLED=false`，确认状态接口报告不可用、播放操作失败，但已有文本答案和引用不消失；验收后恢复配置。
3. 提交空文本和超过 `TTS_MAX_INPUT_CHARS` 的文本，确认在调用上游前返回明确的 4xx 错误。
4. 使用模拟上游返回超时、超大正文和 JSON 错误，确认大小限制、生效的超时及密钥脱敏，不向浏览器转发伪音频。

## 验收结果

2026-07-15 本轮已完成：

- Maven 全 reactor 运行 65 项测试，`0 failures / 0 errors`；追加 TTS 引用清理和结构化引用重编号保护后，`SpeechSynthesisServiceTest` 7 项、`RagRetrievalServiceTest` 5 项分别再次通过。
- 前端生产构建成功；Docker `app` 与 `frontend` 镜像构建成功并已替换运行容器。
- 应用健康检查为 `UP`；Qdrant collection `feisheng_knowledge` 可用，维度 `2048`、距离 `Cosine`、point 数 `127`。
- Admin 试聊完成桌面与 390px 窄屏浏览器检查，移动导航可开关，无横向溢出，控制台无错误。
- 当前环境没有启用 `TTS` 模型，状态接口按设计返回不可用且文本链路保持正常。

仍待真实上游验收：配置一个可用的 `TTS` 模型后验证实际音频内容、格式、字节数和耗时；使用真实业务截图验证“截图事实 + 全局规则”的联合回答与引用。原生视觉 embedding、图搜图和渠道语音发送不属于本阶段范围。
