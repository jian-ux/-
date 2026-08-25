# 钉钉与企业微信接入

当前实现支持钉钉 Stream 机器人的文本、图片和语音消息，以及企业微信自建应用的文本消息。渠道消息会归一为文本后交给现有安全检查、意图识别、业务工具、RAG 和 AI 对话流程。

## 前置条件

- 部署服务必须有平台可访问的公网 HTTPS 域名。
- 域名的 `/gateway/` 路径必须转发到后端；仓库内的 Nginx 配置已经包含该转发。
- 将 `.env.example` 复制为 `.env`，只在 `.env` 中填写真实凭据，不要提交凭据。
- Docker 部署后先确认 `https://你的域名/api/health` 返回 `UP`。

## 钉钉 Stream 机器人

1. 在钉钉开发者后台创建企业内部应用并添加机器人。
2. 在机器人的消息接收配置中选择 **Stream 模式**。Stream 模式由服务主动连接钉钉，不需要填写公网回调 URL。
3. 从“凭证与基础信息”页面取得 Client ID 和 Client Secret。可以直接在管理后台的“渠道配置”中新增钉钉渠道并启用，保存成功后服务会立即验证凭证并建立 Stream 长连接，不需要重启。机器人标识选填，未填写时默认使用 Client ID。

   也可以继续使用 `.env` 作为没有后台渠道配置时的回退：

   ```dotenv
   DINGTALK_STREAM_ENABLED=true
   DINGTALK_CLIENT_ID=应用Client ID
   DINGTALK_CLIENT_SECRET=应用Client Secret
   DINGTALK_ROBOT_CODE=机器人标识（选填，默认使用Client ID）
   ```

   为兼容已有配置，`DINGTALK_CLIENT_SECRET` 为空时会回退使用 `DINGTALK_APP_SECRET`。

4. 重建并启动服务。运行环境需要能够出站访问 `api.dingtalk.com:443`：

   ```bash
   docker compose up -d --build app frontend
   ```

5. 查看后端日志，确认出现 `DingTalk Stream mode enabled` 和 Stream 连接成功信息。
6. 发布应用版本，将机器人加入群聊并 @机器人发送文本或包含清晰文字的图片。群聊中的 `@机器人 + 图片` 会以 `richText` 消息到达，服务会从中提取图片进行 OCR。

钉钉群聊只有 AT 机器人的消息才会投递给机器人，但客户端无法把 AT 和按住说话合并为一条语音消息。因此语音识别应在机器人单聊中使用；群聊中请使用语音转文字后再 AT 机器人。

服务收到消息后会立即确认 Stream 回调，再由有界线程池调用现有 AI/知识库对话流程，并通过消息自带的 `sessionWebhook` 回复原群聊。钉钉重试的相同 `msgId` 会被静默去重，不会再次向用户发送提示；图片和语音也会先去重再下载媒体，避免重复 OCR、ASR 和模型调用。HTTP 回调 `/gateway/channel/dingtalk/message` 仍保留用于兼容旧配置；带 `sessionWebhook` 的文本和媒体消息同样异步回复。

当知识库回复命中已发布图片时，文字仍通过 `sessionWebhook` 回复，图片不再拼进 Markdown。服务会读取 MinIO 中的原图，调用钉钉媒体上传接口取得 `media_id`，再以 `msgKey=sampleImageMsg` 发送独立 Image 消息：单聊发送给回调中的 `senderStaffId`，群聊发送给回调中的 `conversationId`。这条链路不依赖 `APP_PUBLIC_BASE_URL`，临时签名图片 URL 也不会暴露给钉钉客户端。

对话监控中的钉钉人工客服也可以发送图片。客服在工单详情点击“发送图片”并选择本地图片后，管理后台调用：

```text
POST /api/admin/ticket/{ticketId}/reply-image
Content-Type: multipart/form-data
字段：file
```

服务会校验图片类型和 10 MB 大小限制，先上传钉钉媒体接口，再使用独立 `sampleImageMsg` Image 消息发送，并在会话记录中保存文件名、发送状态和失败原因。入站消息会同时保存钉钉原始发送目标（成员 ID、会话类型和群会话 ID）；人工文字和图片回复会沿用该目标，单聊使用 `oToMessages/batchSend`，群聊使用 `groupMessages/send`。历史会话没有目标元数据时，才回退到会话中的客户 ID 发送单聊。

钉钉应用需要在开发者后台开通媒体上传和机器人消息发送所需权限，并发布包含这些权限的应用版本。独立图片链路使用的官方接口为：

```text
POST https://oapi.dingtalk.com/media/upload?type=image
POST https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend
POST https://api.dingtalk.com/v1.0/robot/groupMessages/send
```

图片能力是 OCR 文字识别，适合订单截图、报错截图、票据和包含文字的照片，不是通用视觉理解。图片中没有可识别文字时，机器人会提示客户补充文字说明。

语音优先采用钉钉回调自带的 `recognition`；没有识别文本时，服务下载音频，必要时通过 FFmpeg 转为 16kHz 单声道 WAV，再调用当前启用的 `Speech` 模型。原始媒体和转换文件只写入系统临时目录，处理完成或失败后立即删除，不进入 MinIO、知识库或向量库。

Stream 消息与媒体处理默认配置如下：

```dotenv
DINGTALK_STREAM_PROCESSING_WORKER_THREADS=4
DINGTALK_STREAM_PROCESSING_QUEUE_CAPACITY=100
DINGTALK_MEDIA_WORKER_THREADS=2
DINGTALK_MEDIA_QUEUE_CAPACITY=50
DINGTALK_MEDIA_MAX_IMAGE_BYTES=10485760
DINGTALK_MEDIA_MAX_AUDIO_BYTES=26214400
DINGTALK_MEDIA_MAX_TEXT_CHARS=8000
DINGTALK_MEDIA_FFMPEG_TIMEOUT_SECONDS=60
```

语音回退识别还需要在模型管理中启用一个 `Speech` 类型模型，或配置 `.env` 中的 `SPEECH_ENABLED`、`SPEECH_API_URL`、`SPEECH_API_KEY` 和 `SPEECH_MODEL`。Docker 镜像已经包含 Tesseract 中文/英文语言包和 FFmpeg。

## 企业微信自建应用

1. 在企业微信管理后台创建自建应用，记录企业 ID、应用 Secret 和 AgentId。
2. 在应用的“接收消息”设置中生成 Token 和 EncodingAESKey。
3. 接收消息 URL 必须设置为同一个地址：

   ```text
   https://你的域名/gateway/channel/wechat/message
   ```

   企业微信会先对这个地址发起 GET 校验，保存后再向同一地址 POST 消息。

4. 也可以在管理后台的“渠道配置”中新增“企业微信”渠道，填写企业 ID、应用 Secret、AgentId、回调 Token 和 EncodingAESKey。启用后，网关会优先读取数据库中的这条配置，保存后无需重启即可切换。`.env` 仍可作为没有后台渠道配置时的兼容回退：

   ```dotenv
   WECOM_CORP_ID=企业ID
   WECOM_CORP_SECRET=应用Secret
   WECOM_AGENT_ID=AgentId
   WECOM_CALLBACK_TOKEN=接收消息Token
   WECOM_CALLBACK_AES_KEY=EncodingAESKey
   ```

   管理后台显示的接收消息 URL 路径为 `/gateway/channel/wechat/message`，需要拼接部署域名后填入企业微信后台。

5. 如果使用 `.env` 或刚修改了基础部署配置，重建并启动服务：

   ```bash
   docker compose up -d --build app frontend
   ```

6. 在企业微信后台保存接收消息配置，确保 URL 校验通过；随后从应用可见范围内的成员账号发送文本消息验证回复。后台渠道配置页的“测试连接”只验证 API 凭证和回调字段是否完整，最终仍需企业微信后台完成一次真实回调校验。

## 当前边界

- 钉钉支持文本、图片 OCR 和语音转写；普通文件、视频、卡片以及不含图片的富文本仍不会进入媒体识别流程。
- 群聊支持包含 AT 和图片的 `richText` 消息；其中的首张图片会进入 OCR。群聊原生语音因钉钉 AT 投递限制无法送达机器人，需改用机器人单聊。
- 钉钉文本、客户图片和语音均由有界工作线程异步处理；知识库回复图片会上传钉钉并作为独立 Image 消息发送，最多发送当前回复产生的图片附件数量（默认 3 张）。
- 钉钉照片只提取其中的文字，不识别人物、物体、场景、颜色或其他纯视觉信息。
- `WECOM_CORP_SECRET` 和 `WECOM_AGENT_ID` 已接入主动发送客户端，但现有自动回复仍使用加密被动回复。
- URL 健康检查成功只说明服务可达，最终仍需在钉钉和企业微信后台各完成一次真实回调验证。
