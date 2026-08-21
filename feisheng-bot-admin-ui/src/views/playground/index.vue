<template>
  <div class="playground-container">
    <!-- 左侧聊天区 -->
    <div class="chat-panel">
      <el-card class="chat-card" shadow="never">
        <template #header>
          <div class="chat-header">
            <span>智能试聊调试</span>
            <div class="chat-controls">
              <el-select v-model="selectedModelId" placeholder="选择模型" size="small" style="width:200px" @change="onModelChange">
                <el-option v-for="m in enabledModels" :key="m.id" :label="modelOptionLabel(m)" :value="m.id" />
              </el-select>
              <el-tag size="small" type="info" v-if="currentModelLabel">{{ currentModelLabel }}</el-tag>
              <div class="voice-reply-toggle" :title="speechSynthesisStatus.error ? '语音合成尚未配置' : '自动播放智能语音回复'">
                <span>语音回复</span>
                <el-switch
                  v-model="voiceReplyEnabled"
                  size="small"
                  :disabled="!speechSynthesisStatus.available"
                />
              </div>
            </div>
          </div>
        </template>

        <!-- 消息列表 -->
        <div class="message-area" ref="msgArea">
          <div v-if="messages.length === 0" class="empty-hint">
            <div style="font-size:48px;margin-bottom:12px">💬</div>
            <div>输入问题测试智能回复和知识库命中情况</div>
            <div style="font-size:12px;color:#999;margin-top:8px">
              试试："如何重置密码" / "我要退款" / "系统要求是什么"
            </div>
          </div>

          <div v-for="(msg, idx) in messages" :key="idx" :class="'msg-row msg-' + msg.role">
            <div class="msg-bubble">
              <div class="msg-role">{{ msg.role === 'user' ? '你' : '智能助手' }}</div>
              <img v-if="msg.imagePreview" :src="msg.imagePreview" class="message-image" alt="上传的截图" />
              <div class="msg-content">{{ contentText(msg.content, '暂无回复') }}</div>
              <div v-if="msg.role === 'ai' && msg.attachments?.length" class="reply-images">
                <a
                  v-for="attachment in msg.attachments"
                  :key="attachment.documentId"
                  :href="attachment.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="reply-image-link"
                >
                  <img
                    :src="attachment.url"
                    :alt="attachment.title || '知识库图片'"
                    class="reply-image"
                    loading="lazy"
                    @error="onReplyImageError"
                  />
                  <span>{{ attachment.title || '知识库图片' }}</span>
                </a>
              </div>
              <div v-if="msg.role === 'ai' && speechSynthesisStatus.available" class="message-actions">
                <el-button
                  circle
                  text
                  size="small"
                  :icon="speakingIndex === idx ? VideoPause : VideoPlay"
                  :loading="msg.speechLoading"
                  :title="speakingIndex === idx ? '停止播放' : '播放语音回复'"
                  @click="toggleReplyAudio(msg, idx)"
                />
              </div>
              <div v-if="msg.role === 'ai' && msg.debug" class="msg-debug-bar">
                <el-tag size="small" :type="isKnowledgeSource(msg.debug.source) ? 'success' : 'primary'">
                  {{ sourceLabel(msg.debug.source) }}
                </el-tag>
                <el-tag v-if="msg.debug.answerStatus === 'no_answer'" size="small" type="warning" style="margin-left:6px">
                  无答案
                </el-tag>
                <span v-if="msg.debug.aiDebug?.used" style="font-size:12px;color:#999;margin-left:8px">
                  {{ modelSummaryText(msg.debug.aiDebug) }}
                  · {{ (msg.debug.aiDebug.inputTokens || 0) + (msg.debug.aiDebug.outputTokens || 0) }} 令牌
                  · {{ msg.debug.latencyMs }} 毫秒
                </span>
              </div>
            </div>
          </div>

          <div v-if="loading" class="msg-row msg-ai">
            <div class="msg-bubble"><div class="msg-content typing">智能助手思考中...</div></div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="input-area">
          <div v-if="attachedImage" class="image-attachment">
            <img :src="attachedImage.preview" alt="待提问截图" />
            <div class="image-attachment-meta">
              <strong>{{ contentText(attachedImage.fileName, '已上传截图') }}</strong>
              <span>{{ attachedImage.uploading ? '文字识别中' : `${attachedImage.ocrChars} 字` }}</span>
            </div>
            <el-button :icon="Close" circle text title="移除截图" @click="removeImage" />
          </div>
          <div v-if="lastTranscription" class="transcription-status">
            <div class="transcription-status-icon"><el-icon><Microphone /></el-icon></div>
            <div class="transcription-status-meta">
              <strong>{{ contentText(lastTranscription.fileName, '已上传音频') }}</strong>
              <span>语音识别服务 · {{ lastTranscription.chars }} 字 · {{ lastTranscription.durationMs }} 毫秒</span>
            </div>
            <el-button :icon="Close" circle text title="关闭转写状态" @click="lastTranscription = null" />
          </div>
          <el-input
            v-model="input"
            type="textarea"
            :rows="3"
            placeholder="输入问题，测试智能回复"
            @keyup.enter.exact="send"
            :disabled="loading"
          />
          <div class="input-actions">
            <div class="media-actions">
              <input ref="imageInput" class="hidden-input" type="file" accept="image/png,image/jpeg,image/bmp,image/tiff" @change="uploadImage" />
              <input ref="audioInput" class="hidden-input" type="file" accept="audio/wav,audio/mpeg,.wav,.mp3" @change="uploadAudio" />
              <el-button :icon="Paperclip" title="添加截图" @click="imageInput?.click()" :loading="imageUploading">截图</el-button>
              <el-button :icon="Headset" title="上传音频并转写" @click="audioInput?.click()" :loading="speechUploading" :disabled="recording">音频</el-button>
              <el-button
                :icon="recording ? VideoPause : Microphone"
                :type="recording ? 'danger' : 'default'"
                :title="recording ? '停止录音并转写' : '开始录音'"
                :disabled="speechUploading"
                @click="toggleRecording"
              >
                {{ recording ? formatDuration(recordingSeconds) : '录音' }}
              </el-button>
            </div>
            <div class="command-actions">
              <el-button @click="clear">清空对话</el-button>
              <el-button type="primary" @click="send" :loading="loading" :disabled="!input.trim() || imageUploading || speechUploading || recording">
                发送
              </el-button>
            </div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 右侧调试面板 -->
    <div class="debug-panel" v-if="lastDebug">
      <el-card shadow="never">
        <template #header><span>🔍 调试详情</span></template>

        <!-- 安全预检 -->
        <div class="debug-section">
          <div class="debug-title">🛡️ 安全预检</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="状态">
              <el-tag :type="lastDebug.safetyPreCheck.blocked ? 'danger' : 'success'" size="small">
                {{ lastDebug.safetyPreCheck.blocked ? '已拦截' : '通过' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="动作">{{ safetyActionText(lastDebug.safetyPreCheck.action) }}</el-descriptions-item>
            <el-descriptions-item v-if="lastDebug.safetyPreCheck.hitRules.length" label="命中规则">
              <span v-for="r in lastDebug.safetyPreCheck.hitRules" :key="r" style="color:#e6a23c">{{ ruleDisplayText(r) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- FAQ 命中 -->
        <div class="debug-section">
          <div class="debug-title">📚 知识库命中</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="命中">
              <el-tag :type="lastDebug.faqHit ? 'success' : 'info'" size="small">
                {{ lastDebug.faqHit ? '✅ 是' : '❌ 否' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="检索增强上下文">
              {{ lastDebug.aiDebug?.ragContextChars || lastDebug.aiDebug?.knowledgeContextChars || 0 }} 字符
            </el-descriptions-item>
            <el-descriptions-item label="决策">
              {{ retrievalDecisionText(lastDebug.retrieval?.decision || lastDebug.answerStatus) }}
            </el-descriptions-item>
            <el-descriptions-item label="输入模态">
              {{ modalityLabel(lastDebug.inputModality) }}
            </el-descriptions-item>
            <el-descriptions-item label="置信度">
              {{ percent(lastDebug.confidence) }}
            </el-descriptions-item>
          </el-descriptions>
          <div v-if="lastDebug.faqMatches.length" style="margin-top:8px">
            <div v-for="(m, i) in lastDebug.faqMatches" :key="i" class="faq-match-item">
              <div class="faq-score-bar">
                <span :style="{color: m.score > 0.8 ? '#67c23a' : m.score > 0.5 ? '#e6a23c' : '#909399'}">
                  {{ percent(m.score) }}
                </span>
                <span style="font-size:11px;color:#999">
                  向量:{{ percent(m.vectorScore) }} 关键词:{{ percent(m.keywordScore) }}
                </span>
              </div>
              <div><strong>来源：</strong> {{ knowledgeSourceText(m) }}</div>
              <div><strong>置信度：</strong> <el-tag :type="m.confidence === 'high' ? 'success' : m.confidence === 'medium' ? 'warning' : 'info'" size="small">{{ confidenceText(m.confidence) }}</el-tag></div>
            </div>
          </div>
          <div v-if="lastDebug.citations?.length" class="citation-list">
            <div class="debug-title">引用来源</div>
            <div v-for="(citation, citationIndex) in lastDebug.citations" :key="citation.id" class="citation-item">
              <strong>第 {{ citationIndex + 1 }} 项 · {{ citation.sourceType === 'image' ? '图片内容' : citationTitleText(citation.title) }}</strong>
              <span>{{ percent(citation.score) }}</span>
              <p>{{ contentText(citation.snippet, '知识库摘要') }}</p>
            </div>
          </div>
        </div>

        <!-- 智能模型调用详情 -->
        <div class="debug-section" v-if="lastDebug.aiDebug?.used">
          <div class="debug-title">智能模型调用详情</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="供应商">{{ providerText(lastDebug.aiDebug.providerCode) }}</el-descriptions-item>
            <el-descriptions-item label="模型">{{ modelNameText(lastDebug.aiDebug.model, lastDebug.aiDebug.providerCode) }}</el-descriptions-item>
            <el-descriptions-item label="输入令牌">{{ lastDebug.aiDebug.inputTokens }}</el-descriptions-item>
            <el-descriptions-item label="输出令牌">{{ lastDebug.aiDebug.outputTokens }}</el-descriptions-item>
            <el-descriptions-item label="总令牌">{{ (lastDebug.aiDebug.inputTokens || 0) + (lastDebug.aiDebug.outputTokens || 0) }}</el-descriptions-item>
            <el-descriptions-item label="预估成本">{{ ((lastDebug.aiDebug.costCents || 0) / 100).toFixed(4) }} 元</el-descriptions-item>
            <el-descriptions-item label="调用成功">
              <el-tag :type="lastDebug.aiDebug.success ? 'success' : 'danger'" size="small">
                {{ lastDebug.aiDebug.success ? '是' : '否' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 智能输出安全后检 -->
          <div v-if="lastDebug.aiDebug.safetyPostCheck" style="margin-top:8px">
            <div class="debug-title" style="font-size:13px">智能输出安全后检</div>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="状态">
                <el-tag :type="lastDebug.aiDebug.safetyPostCheck.blocked ? 'danger' : 'success'" size="small">
                  {{ lastDebug.aiDebug.safetyPostCheck.blocked ? '需修正' : '通过' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="动作">{{ safetyActionText(lastDebug.aiDebug.safetyPostCheck.action) }}</el-descriptions-item>
              <el-descriptions-item v-if="lastDebug.aiDebug.safetyPostCheck.hitRules?.length" label="命中规则">
                <span v-for="r in lastDebug.aiDebug.safetyPostCheck.hitRules" :key="r" style="color:#e6a23c">{{ ruleDisplayText(r) }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </div>

        <!-- 延迟 -->
        <div class="debug-section">
          <div class="debug-title">⏱️ 响应延迟</div>
          <div style="font-size:24px;font-weight:bold;color:#409eff">{{ lastDebug.latencyMs }}<span style="font-size:14px"> 毫秒</span></div>
          <el-descriptions v-if="lastDebug.stageLatencies" :column="1" size="small" border style="margin-top:10px">
            <el-descriptions-item label="知识检索">{{ lastDebug.stageLatencies.retrievalMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="向量生成">{{ lastDebug.stageLatencies.embeddingMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="向量检索">{{ lastDebug.stageLatencies.vectorSearchMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="稀疏检索">{{ lastDebug.stageLatencies.sparseSearchMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="相关性重排">{{ lastDebug.stageLatencies.rerankMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="模型生成">{{ lastDebug.stageLatencies.modelMs || 0 }} 毫秒</el-descriptions-item>
            <el-descriptions-item label="其他处理">{{ lastDebug.stageLatencies.otherMs || 0 }} 毫秒</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import request from '../../api/index.js'
import { createChatSessionId } from '../../utils/chatSession.js'
import { ElMessage } from 'element-plus'
import { Paperclip, Close, Headset, Microphone, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { confidenceText, contentText, providerText, safetyActionText } from '../../utils/displayText.js'

const input = ref('')
const messages = ref([])
const loading = ref(false)
const lastDebug = ref(null)
const msgArea = ref(null)
const enabledModels = ref([])
const selectedModelId = ref(null)
const imageInput = ref(null)
const attachedImage = ref(null)
const imageUploading = ref(false)
const audioInput = ref(null)
const speechUploading = ref(false)
const lastTranscription = ref(null)
const recording = ref(false)
const recordingSeconds = ref(0)
const speechSynthesisStatus = ref({ available: false, error: '' })
const voiceReplyEnabled = ref(false)
const speakingIndex = ref(null)
const sessionId = ref(createChatSessionId())
let mediaRecorder = null
let recordingStream = null
let recordingTimer = null
let recordedChunks = []
let discardRecording = false
let replyAudio = null
let replyAudioMessage = null
let speechSequence = 0
const MAX_SPEECH_DURATION_SECONDS = 30
const CHAT_TIMEOUT_MS = 90000

const isKnowledgeSource = source => ['faq', 'rag', 'rag_ai'].includes(source)

const percent = value => `${(Number(value || 0) * 100).toFixed(0)}%`

function modalityLabel(value) {
  if (value === 'image') return '截图文字识别'
  if (value === 'audio') return '语音转写'
  return '文本'
}

function sourceLabel(source) {
  if (source === 'faq') return '常见问题命中'
  if (source === 'rag') return '知识库直答'
  if (source === 'rag_ai') return '知识库增强'
  if (source === 'capability') return '业务能力'
  if (source === 'no_answer') return '知识库无答案'
  if (source === 'out_of_scope') return '非点签业务'
  if (source === 'safety') return '安全拦截'
  if (source === 'error') return '服务异常'
  return '智能生成'
}

function modelNameText(value, provider) {
  if (value && !/[A-Za-z]/.test(value)) return value
  return `${providerText(provider)}模型`
}

function modelOptionLabel(model) {
  return modelNameText(model.modelName, model.provider) + (model.isDefault ? ' · 默认' : '')
}

function modelSummaryText(debug) {
  return `${providerText(debug.providerCode)} · ${modelNameText(debug.model, debug.providerCode)}`
}

function retrievalDecisionText(value) {
  return {
    faq: '常见问题回答',
    rag: '知识库回答',
    rag_ai: '知识库增强回答',
    no_answer: '暂无答案',
    out_of_scope: '超出服务范围',
    safety: '安全拦截'
  }[String(value || '').toLowerCase()] || '系统决策'
}

function ruleDisplayText(value) {
  return value && !/[A-Za-z]/.test(value) ? value : '已命中安全规则'
}

function knowledgeSourceText(match) {
  if (match.question) return match.question
  return citationTitleText(match.title)
}

function citationTitleText(value) {
  return value && !/[A-Za-z]/.test(value) ? value : '知识库内容'
}

const currentModelLabel = computed(() => {
  const m = enabledModels.value.find(m => m.id === selectedModelId.value)
  return m ? `${providerText(m.provider)} · ${modelNameText(m.modelName, m.provider)}` : ''
})

async function loadModels() {
  try {
    const r = await request.get('/admin/ai/model/enabled')
    enabledModels.value = r.data || []
    // 自动选择默认模型。
    const def = enabledModels.value.find(m => m.isDefault)
    if (def) selectedModelId.value = def.id
    else if (enabledModels.value.length > 0) selectedModelId.value = enabledModels.value[0].id
  } catch { enabledModels.value = [] }
}

async function loadSpeechSynthesisStatus() {
  try {
    const result = await request.get('/admin/playground/speech/synthesis/status')
    speechSynthesisStatus.value = result.data || { available: false, error: '语音合成状态未知' }
  } catch {
    speechSynthesisStatus.value = { available: false, error: '语音合成不可用' }
  }
}

function onModelChange() {
  // Model changed, no action needed (used on next send)
}

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return

  const image = attachedImage.value
  const inputModality = image?.id ? 'image' : lastTranscription.value ? 'audio' : 'text'
  messages.value.push({ role: 'user', content: text, imagePreview: image?.preview })
  input.value = ''
  attachedImage.value = null
  lastTranscription.value = null
  loading.value = true
  lastDebug.value = null

  try {
    const payload = { text, inputModality, sessionId: sessionId.value }
    if (selectedModelId.value) payload.modelId = selectedModelId.value
    if (image?.id) payload.imageId = image.id
    const r = await request.post('/admin/playground/chat', payload, { timeout: CHAT_TIMEOUT_MS })
    const data = r.data
    lastDebug.value = data

    const aiMessage = {
      role: 'ai',
      content: data.reply || '(无回复)',
      attachments: Array.isArray(data.attachments) ? data.attachments : [],
      debug: data,
      speechLoading: false,
      audioUrl: null
    }
    messages.value.push(aiMessage)
    if (voiceReplyEnabled.value) {
      playReplyAudio(aiMessage, messages.value.length - 1, true)
    }
  } catch(e) {
    const reason = e.code === 'ECONNABORTED'
      ? '请求超时，请检查智能模型或向量服务连接'
      : '服务暂时不可用'
    messages.value.push({ role: 'ai', content: '请求失败：' + reason })
  } finally {
    loading.value = false
    await nextTick()
    if (msgArea.value) msgArea.value.scrollTop = msgArea.value.scrollHeight
  }
}

function onReplyImageError(event) {
  if (event?.currentTarget?.parentElement) event.currentTarget.parentElement.hidden = true
}

function toggleReplyAudio(message, index) {
  if (speakingIndex.value === index) {
    stopReplyAudio()
    return
  }
  playReplyAudio(message, index, false)
}

async function playReplyAudio(message, index, automatic) {
  stopReplyAudio()
  if (replyAudioMessage && replyAudioMessage !== message && replyAudioMessage.audioUrl) {
    URL.revokeObjectURL(replyAudioMessage.audioUrl)
    replyAudioMessage.audioUrl = null
  }
  replyAudioMessage = message
  const requestId = ++speechSequence
  message.speechLoading = true
  try {
    if (!message.audioUrl) {
      const audioBlob = await request.post(
        '/admin/playground/speech/synthesis',
        { text: message.content },
        { responseType: 'blob', timeout: 150000 }
      )
      if (!(audioBlob instanceof Blob) || audioBlob.size === 0) {
        throw new Error('语音合成返回空音频')
      }
      if (requestId !== speechSequence) return
      message.audioUrl = URL.createObjectURL(audioBlob)
    }
    if (requestId !== speechSequence) return
    replyAudio = new Audio(message.audioUrl)
    speakingIndex.value = index
    replyAudio.onended = () => finishReplyAudio(index)
    replyAudio.onerror = () => {
      finishReplyAudio(index)
      ElMessage.error('语音播放失败')
    }
    await replyAudio.play()
  } catch (error) {
    if (requestId === speechSequence) {
      stopReplyAudio()
      if (automatic && error?.name === 'NotAllowedError') {
        ElMessage.warning('浏览器已阻止自动播放，请点击回答旁的播放按钮')
      }
    }
  } finally {
    message.speechLoading = false
  }
}

function finishReplyAudio(index) {
  if (speakingIndex.value !== index) return
  replyAudio = null
  speakingIndex.value = null
}

function stopReplyAudio() {
  speechSequence += 1
  if (replyAudio) {
    replyAudio.onended = null
    replyAudio.onerror = null
    replyAudio.pause()
    replyAudio.currentTime = 0
  }
  replyAudio = null
  speakingIndex.value = null
}

function releaseReplyAudioUrls() {
  stopReplyAudio()
  for (const message of messages.value) {
    if (message.audioUrl) {
      URL.revokeObjectURL(message.audioUrl)
      message.audioUrl = null
    }
  }
  replyAudioMessage = null
}

async function uploadImage(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('截图不能超过 10 兆字节')
    return
  }
  removeImage()
  const preview = URL.createObjectURL(file)
  attachedImage.value = { fileName: file.name, preview, uploading: true, ocrChars: 0 }
  imageUploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const result = await request.post('/admin/playground/image', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 90000
    })
    attachedImage.value = {
      ...attachedImage.value,
      id: result.data.id,
      uploading: false,
      ocrChars: result.data.ocrChars,
      ocrText: result.data.ocrText
    }
    ElMessage.success(`文字识别完成，共识别 ${result.data.ocrChars} 字`)
  } catch {
    URL.revokeObjectURL(preview)
    attachedImage.value = null
  } finally {
    imageUploading.value = false
  }
}

async function uploadAudio(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) await transcribeAudio(file)
}

async function transcribeAudio(file) {
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['wav', 'mp3'].includes(extension)) {
    ElMessage.warning('当前语音服务不支持此文件格式')
    return
  }
  if (file.size > 25 * 1024 * 1024) {
    ElMessage.warning('音频不能超过 25 兆字节')
    return
  }
  const duration = await readAudioDuration(file)
  if (duration > MAX_SPEECH_DURATION_SECONDS) {
    ElMessage.warning(`音频不能超过 ${MAX_SPEECH_DURATION_SECONDS} 秒`)
    return
  }
  speechUploading.value = true
  lastTranscription.value = null
  try {
    const form = new FormData()
    form.append('file', file)
    const result = await request.post('/admin/playground/speech', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 150000
    })
    input.value = input.value.trim()
      ? `${input.value.trim()}\n${result.data.text}`
      : result.data.text
    lastTranscription.value = { ...result.data, fileName: file.name }
    ElMessage.success(`转写完成，识别 ${result.data.chars} 字`)
  } catch {
    lastTranscription.value = null
  } finally {
    speechUploading.value = false
  }
}

async function toggleRecording() {
  if (recording.value) {
    stopRecording()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    ElMessage.warning('当前浏览器不支持录音')
    return
  }
  try {
    recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']
      .find(type => MediaRecorder.isTypeSupported(type))
    mediaRecorder = mimeType
      ? new MediaRecorder(recordingStream, { mimeType })
      : new MediaRecorder(recordingStream)
    recordedChunks = []
    discardRecording = false
    mediaRecorder.ondataavailable = event => {
      if (event.data.size > 0) recordedChunks.push(event.data)
    }
    mediaRecorder.onstop = finishRecording
    mediaRecorder.start(1000)
    recording.value = true
    recordingSeconds.value = 0
    recordingTimer = window.setInterval(() => {
      recordingSeconds.value += 1
      if (recordingSeconds.value >= MAX_SPEECH_DURATION_SECONDS) stopRecording()
    }, 1000)
  } catch (error) {
    releaseRecordingStream()
    ElMessage.error(error.name === 'NotAllowedError' ? '麦克风权限未授予' : '无法开始录音')
  }
}

function stopRecording() {
  if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  recording.value = false
  clearRecordingTimer()
}

function cancelRecording() {
  discardRecording = true
  if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  recording.value = false
  clearRecordingTimer()
  releaseRecordingStream()
}

async function finishRecording() {
  const mimeType = mediaRecorder?.mimeType || 'audio/webm'
  const blob = new Blob(recordedChunks, { type: mimeType })
  mediaRecorder = null
  recordedChunks = []
  releaseRecordingStream()
  if (discardRecording) return
  if (blob.size === 0) return
  try {
    const file = await recordingToWav(blob)
    await transcribeAudio(file)
  } catch {
    ElMessage.error('录音格式转换失败，请改用受支持的音频文件上传')
  }
}

async function recordingToWav(blob) {
  const AudioContextClass = window.AudioContext || window.webkitAudioContext
  if (!AudioContextClass) throw new Error('AudioContext unavailable')
  const context = new AudioContextClass()
  try {
    const audioBuffer = await context.decodeAudioData(await blob.arrayBuffer())
    const mono = mixToMono(audioBuffer)
    const samples = resampleAudio(mono, audioBuffer.sampleRate, 16000)
    const wav = encodePcmWav(samples, 16000)
    return new File([wav], `recording-${Date.now()}.wav`, { type: 'audio/wav' })
  } finally {
    await context.close()
  }
}

function mixToMono(audioBuffer) {
  const output = new Float32Array(audioBuffer.length)
  for (let channel = 0; channel < audioBuffer.numberOfChannels; channel += 1) {
    const input = audioBuffer.getChannelData(channel)
    for (let i = 0; i < input.length; i += 1) output[i] += input[i]
  }
  if (audioBuffer.numberOfChannels > 1) {
    for (let i = 0; i < output.length; i += 1) output[i] /= audioBuffer.numberOfChannels
  }
  return output
}

function resampleAudio(input, sourceRate, targetRate) {
  if (sourceRate === targetRate) return input
  const ratio = sourceRate / targetRate
  const output = new Float32Array(Math.ceil(input.length / ratio))
  for (let i = 0; i < output.length; i += 1) {
    const position = i * ratio
    const left = Math.floor(position)
    const right = Math.min(left + 1, input.length - 1)
    const weight = position - left
    output[i] = input[left] * (1 - weight) + input[right] * weight
  }
  return output
}

function encodePcmWav(samples, sampleRate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)
  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeAscii(view, 36, 'data')
  view.setUint32(40, samples.length * 2, true)
  for (let i = 0; i < samples.length; i += 1) {
    const sample = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(44 + i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
  }
  return buffer
}

function writeAscii(view, offset, value) {
  for (let i = 0; i < value.length; i += 1) view.setUint8(offset + i, value.charCodeAt(i))
}

function readAudioDuration(file) {
  return new Promise(resolve => {
    const url = URL.createObjectURL(file)
    const audio = document.createElement('audio')
    let settled = false
    const finish = value => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      audio.onloadedmetadata = null
      audio.onerror = null
      audio.removeAttribute('src')
      audio.load()
      URL.revokeObjectURL(url)
      resolve(Number.isFinite(value) ? value : 0)
    }
    const timeout = window.setTimeout(() => finish(0), 5000)
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => finish(audio.duration)
    audio.onerror = () => finish(0)
    audio.src = url
  })
}

function clearRecordingTimer() {
  if (recordingTimer) window.clearInterval(recordingTimer)
  recordingTimer = null
}

function releaseRecordingStream() {
  recordingStream?.getTracks().forEach(track => track.stop())
  recordingStream = null
}

function formatDuration(seconds) {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0')
  const rest = (seconds % 60).toString().padStart(2, '0')
  return `${minutes}:${rest}`
}

function removeImage() {
  if (attachedImage.value?.preview) URL.revokeObjectURL(attachedImage.value.preview)
  attachedImage.value = null
}

function clear() {
  if (recording.value) cancelRecording()
  removeImage()
  releaseReplyAudioUrls()
  for (const message of messages.value) {
    if (message.imagePreview) URL.revokeObjectURL(message.imagePreview)
  }
  messages.value = []
  sessionId.value = createChatSessionId()
  lastDebug.value = null
  lastTranscription.value = null
}

onMounted(() => {
  loadModels()
  loadSpeechSynthesisStatus()
})
onBeforeUnmount(() => {
  cancelRecording()
  removeImage()
  releaseReplyAudioUrls()
})
</script>

<style scoped>
.playground-container { display:flex; gap:15px; height:calc(100vh - 120px); }
.chat-panel { flex:1; display:flex; flex-direction:column; min-width:0; }
.chat-card { display:flex; flex-direction:column; height:100%; }
.chat-card :deep(.el-card__body) { flex:1; display:flex; flex-direction:column; padding:0; overflow:hidden; }
.chat-header { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.chat-controls { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:8px; }
.voice-reply-toggle { display:flex; align-items:center; gap:6px; color:#606266; font-size:12px; white-space:nowrap; }
.message-area { flex:1; overflow-y:auto; padding:15px; min-height:200px; }
.empty-hint { text-align:center; color:#999; padding:60px 20px; }
.msg-row { display:flex; margin-bottom:16px; }
.msg-user { justify-content:flex-end; }
.msg-ai { justify-content:flex-start; }
.msg-bubble { max-width:80%; padding:10px 14px; border-radius:10px; position:relative; }
.msg-user .msg-bubble { background:#409eff; color:#fff; border-bottom-right-radius:3px; }
.msg-ai .msg-bubble { background:#f0f0f0; color:#333; border-bottom-left-radius:3px; }
.msg-role { font-size:11px; font-weight:bold; margin-bottom:4px; opacity:0.7; }
.msg-content { line-height:1.6; white-space:pre-wrap; word-break:break-word; }
.message-actions { display:flex; justify-content:flex-end; height:28px; margin-top:4px; }
.message-actions :deep(.el-button) { width:28px; height:28px; }
.msg-debug-bar { margin-top:8px; padding-top:8px; border-top:1px solid rgba(0,0,0,0.1); }
.typing { opacity:0.5; }
.input-area { padding:15px; border-top:1px solid #ebeef5; background:#fafafa; }
.hidden-input { display:none; }
.image-attachment { display:flex; align-items:center; gap:10px; margin-bottom:10px; padding:8px; border:1px solid #dcdfe6; background:#fff; border-radius:6px; }
.image-attachment img { width:64px; height:48px; object-fit:cover; border-radius:4px; }
.image-attachment-meta { display:flex; flex:1; min-width:0; flex-direction:column; }
.image-attachment-meta strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }
.image-attachment-meta span { color:#909399; font-size:12px; }
.transcription-status { display:flex; align-items:center; gap:10px; margin-bottom:10px; padding:8px; border:1px solid #dcdfe6; background:#fff; border-radius:6px; }
.transcription-status-icon { display:grid; place-items:center; width:36px; height:36px; flex:0 0 36px; color:#409eff; background:#ecf5ff; border-radius:50%; }
.transcription-status-meta { display:flex; flex:1; min-width:0; flex-direction:column; }
.transcription-status-meta strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }
.transcription-status-meta span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#909399; font-size:12px; }
.message-image { display:block; max-width:280px; max-height:180px; object-fit:contain; margin-bottom:8px; border-radius:6px; background:#fff; }
.reply-images { display:flex; flex-wrap:wrap; gap:8px; margin-top:10px; }
.reply-image-link { display:flex; width:min(280px, 100%); flex-direction:column; color:#606266; text-decoration:none; }
.reply-image { display:block; width:100%; max-height:240px; aspect-ratio:4 / 3; object-fit:contain; border:1px solid #dcdfe6; border-radius:6px; background:#fff; }
.reply-image-link span { overflow:hidden; padding-top:4px; font-size:12px; line-height:18px; text-overflow:ellipsis; white-space:nowrap; }
.input-actions { display:flex; justify-content:space-between; gap:8px; margin-top:10px; }
.media-actions, .command-actions { display:flex; flex-wrap:wrap; gap:8px; }
.debug-panel { width:340px; flex-shrink:0; overflow-y:auto; }
.debug-section { margin-bottom:16px; }
.debug-title { font-weight:bold; font-size:14px; margin-bottom:8px; color:#303133; }
.faq-match-item { background:#f0f9eb; padding:8px; border-radius:6px; margin-bottom:6px; font-size:13px; }
.faq-match-item div { margin:2px 0; }
.faq-score-bar { display:flex; justify-content:space-between; align-items:center; font-size:18px; font-weight:bold; }
.citation-list { margin-top:12px; }
.citation-item { padding:8px 0; border-bottom:1px solid #ebeef5; font-size:13px; }
.citation-item strong { display:inline-block; max-width:75%; }
.citation-item span { float:right; color:#409eff; }
.citation-item p { margin:4px 0 0; color:#606266; line-height:1.5; }
@media (max-width: 900px) {
  .playground-container { height:auto; min-height:calc(100vh - 120px); }
  .debug-panel { display:none; }
  .input-actions { flex-direction:column; }
  .command-actions { justify-content:flex-end; }
}
@media (max-width: 600px) {
  .chat-header { align-items:flex-start; flex-direction:column; }
  .chat-controls { justify-content:flex-start; width:100%; }
}
</style>
