# RAG 第四阶段：语音转文字

## 交付范围

第四阶段把语音输入转换为可编辑文本，并复用现有文本对话链路：

- 试聊支持上传音频文件并转写。
- 支持浏览器麦克风录音，停止后自动转写。
- 转写结果先回填输入框，由用户校对后再发送。
- 发送后继续使用既有安全检查、RAG 检索、引用和无答案策略。
- 音频仅写入操作系统临时目录，转写完成或失败后立即删除，不进入 MinIO 和知识库。

本阶段不包含语音回复、音频向量检索和渠道语音消息下载，这些属于第六阶段及渠道接入工作。

## 转写服务

后端使用 OpenAI-compatible `POST /audio/transcriptions` 协议，默认模型为 `whisper-1`。可连接 OpenAI、本地 Whisper 或智谱 GLM-ASR-2512。

模型配置有两种方式，数据库配置优先：

1. 在“AI 模型配置”新增并启用 `Speech` 类型模型，填写模型名称、API 地址和密钥。
2. 使用环境变量配置，并显式设置 `SPEECH_ENABLED=true`。

每种模型类型独立设置默认项，`Speech`、`Embedding` 和 `LLM` 不会互相覆盖默认模型。

环境变量示例：

```dotenv
SPEECH_ENABLED=true
SPEECH_API_URL=https://api.openai.com/v1/audio/transcriptions
SPEECH_API_KEY=replace-with-api-key
SPEECH_MODEL=whisper-1
SPEECH_LANGUAGE=zh
SPEECH_PROMPT=
SPEECH_MAX_BYTES=26214400
SPEECH_CONNECT_TIMEOUT_SECONDS=10
SPEECH_TIMEOUT_SECONDS=120
```

本地兼容服务可以将 `SPEECH_API_URL` 配置为完整转写地址或 `/v1` 基础地址。若模型配置误填为 `/chat/completions` 或 `/embeddings`，服务会转换成 `/audio/transcriptions`。

当前 Docker 环境使用智谱语音识别：

```dotenv
SPEECH_ENABLED=true
SPEECH_API_URL=https://open.bigmodel.cn/api/paas/v4/audio/transcriptions
SPEECH_API_KEY=replace-with-zhipu-api-key
SPEECH_MODEL=glm-asr-2512
SPEECH_LANGUAGE=zh
```

`glm-4-voice` 是端到端语音对话模型，不用于文件转写。智谱 GLM-ASR-2512 单个音频限制为 25MB、30 秒。

## 接口

查看配置状态：

```text
GET /api/admin/playground/speech/status
```

上传音频并转写：

```text
POST /api/admin/playground/speech
Content-Type: multipart/form-data
file=<audio>
```

成功响应的数据部分：

```json
{
  "text": "如何重置密码？",
  "chars": 7,
  "model": "glm-asr-2512",
  "provider": "openai-compatible",
  "language": "zh",
  "audioBytes": 123456,
  "durationMs": 1420
}
```

## 格式与安全

通用后端支持 `flac`、`mp3`、`mp4`、`mpeg`、`mpga`、`m4a`、`ogg`、`wav`、`webm`，默认上限为 25MB。当前智谱 GLM-ASR-2512 只接受 WAV、MP3。

- 扩展名使用白名单，并检查 RIFF/WAVE、ID3/MPEG、MP4、Ogg、FLAC 或 WebM 文件头。
- 上传文件名会去除路径并过滤特殊字符。
- 服务端不信任浏览器传入的 MIME 类型，根据已校验扩展名生成上游请求类型。
- 转写请求有连接和读取超时，第三方错误正文最多返回 500 个字符。
- 转写文本作为用户输入进入原有安全和 RAG 链路，不作为系统指令执行。
- 当前智谱配置下，浏览器录音和上传音频最长 30 秒；页面退出或清空对话时会停止麦克风并丢弃未完成录音。
- 浏览器麦克风通常产生 WebM/Opus 或 MP4/AAC；页面会在本地解码并转换成 16kHz 单声道 PCM WAV 后上传。

## 使用流程

1. 配置并启用一个 `Speech` 模型，或设置上述环境变量。
2. 打开“AI 试聊调试”。
3. 点击“音频”选择文件，或点击“录音”并在结束时再次点击。
4. 检查输入框中的转写文本，必要时编辑。
5. 点击“发送”，查看既有 RAG 决策、引用与模型调试信息。

## 自动验收

项目提供端到端验收脚本。脚本读取 `.env` 中的管理员账号，登录后台、检查转写状态、生成一段 WAV 测试语音并通过应用接口调用真实上游：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-speech.ps1
```

也可以传入真实业务录音：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-speech.ps1 `
  -AudioPath C:\path\to\sample.wav
```

验收成功时输出 `provider`、`model`、转写耗时和文本，不输出 API 密钥。自动化单元测试使用模拟上游，额外校验 multipart 字段、鉴权头、模型和语言参数。

## 验收结果

第四阶段已于 2026-07-15 在真实 Docker 环境完成验收：

- 后台登录、JWT 鉴权和 `/speech/status` 接口正常。
- 应用容器正确加载语音开关、地址、模型、语言、超时和文件上限配置。
- 中文 WAV 已通过上传校验并进入 OpenAI-compatible 客户端。
- 已按智谱官方协议配置 `https://open.bigmodel.cn/api/paas/v4/audio/transcriptions` 和 `glm-asr-2512`。
- 前端已按 GLM-ASR-2512 限制，将录音和上传音频上限统一为 30 秒。
- 麦克风录音会转换为 GLM-ASR-2512 支持的 WAV；服务端会拒绝该模型不支持的其他格式，避免上游 502。
- 服务会在状态检查阶段拒绝含非法 HTTP 头字符的密钥，上游错误中的密钥及掩码片段也会被脱敏。
- 系统中文合成语音通过完整应用链路转写，音频 `294512` 字节，上游处理约 `1905ms`，结果为“飞升智能客服语音转写验收，请问如何重置登录密码？”。
- 智谱官方自然语音样本通过完整应用链路转写，音频 `958592` 字节，上游处理约 `1334ms`，准确识别完整导航请求。

验收脚本两次均返回 `success: true`，临时音频在请求结束后删除，第四阶段完成。
