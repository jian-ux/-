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
3. 从“凭证与基础信息”页面取得 Client ID 和 Client Secret，在 `.env` 中配置：

   ```dotenv
   DINGTALK_STREAM_ENABLED=true
   DINGTALK_CLIENT_ID=应用Client ID
   DINGTALK_CLIENT_SECRET=应用Client Secret
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

4. 在 `.env` 中填写：

   ```dotenv
   WECOM_CORP_ID=企业ID
   WECOM_CORP_SECRET=应用Secret
   WECOM_AGENT_ID=AgentId
   WECOM_CALLBACK_TOKEN=接收消息Token
   WECOM_CALLBACK_AES_KEY=EncodingAESKey
   ```

5. 重建并启动服务：

   ```bash
   docker compose up -d --build app frontend
   ```

6. 保存企业微信接收消息配置，确保 URL 校验通过；随后从应用可见范围内的成员账号发送文本消息验证回复。

## 当前边界

- 钉钉支持文本、图片 OCR 和语音转写；普通文件、视频、卡片以及不含图片的富文本仍不会进入媒体识别流程。
- 群聊支持包含 AT 和图片的 `richText` 消息；其中的首张图片会进入 OCR。群聊原生语音因钉钉 AT 投递限制无法送达机器人，需改用机器人单聊。
- 钉钉文本、图片和语音均由有界工作线程异步处理并使用 `sessionWebhook` 回复；企业微信仍由 HTTP 回调同步生成回复。
- 钉钉照片只提取其中的文字，不识别人物、物体、场景、颜色或其他纯视觉信息。
- `WECOM_CORP_SECRET` 和 `WECOM_AGENT_ID` 已接入主动发送客户端，但现有自动回复仍使用加密被动回复。
- URL 健康检查成功只说明服务可达，最终仍需在钉钉和企业微信后台各完成一次真实回调验证。
