<template>
  <el-card :class="['conversation-detail-card', { 'chat-focus-mode': chatFocusMode }]">
    <template #header>
      <div class="page-header">
        <span class="page-title">
          <span>会话详情 #{{ conversation?.id }}</span>
          <span v-if="conversation?.customerName" class="customer-name-label">
            客户：{{ contentText(conversation.customerName, '未知客户') }}
          </span>
          <span v-if="conversation?.channelType" class="conversation-context-label">
            渠道：{{ channelTypeText(conversation.channelType) }}
          </span>
          <span v-if="conversation?.createTime" class="conversation-context-label">
            创建：{{ formatDateTime(conversation.createTime) }}
          </span>
        </span>
        <div class="header-actions">
          <el-button
            v-if="conversation?.status==='transferred'"
            @click="chatFocusMode = !chatFocusMode"
          >{{ chatFocusMode ? '显示详情' : '专注聊天' }}</el-button>
          <el-button
            v-if="messages.length"
            type="success"
            :icon="MagicStick"
            :loading="aiLoading"
            @click="getAiDraft"
          >智能建议</el-button>
          <el-button
            v-if="conversation?.status==='active'"
            type="warning"
            :icon="Switch"
            @click="transfer"
          >转人工</el-button>
          <el-button
            v-if="conversation?.status==='active'"
            type="danger"
            :icon="CircleClose"
            @click="closeConversation"
          >关闭会话</el-button>
          <el-button :icon="Back" @click="$router.push('/conversation')">返回列表</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="aiDraft"
      type="success"
      :closable="true"
      @close="aiDraft=''"
      class="draft-alert"
    >
      <template #title><strong>智能建议回复</strong></template>
      <div class="draft-content">{{ contentText(aiDraft, '暂无智能建议') }}</div>
      <el-button size="small" type="primary" :icon="EditPen" @click="adoptAiDraft">
        填入人工回复
      </el-button>
    </el-alert>

    <section v-if="conversation?.status==='transferred' && ticket" class="handoff-panel">
      <div class="handoff-header">
        <div class="handoff-title">
          <span>人工接管</span>
          <el-tag :type="ticketStatusTag(ticket.status)" size="small">
            {{ ticketStatusText(ticket.status) }}
          </el-tag>
          <el-tag :type="priorityTag(ticket.priority)" effect="plain" size="small">
            {{ priorityText(ticket.priority) }}
          </el-tag>
        </div>
        <div class="handoff-meta">
          <span>{{ agentText(ticket.assigneeName, ticket.assigneeId) }}</span>
          <span v-if="ticket.slaDeadline" :class="slaClass">{{ slaText }}</span>
        </div>
      </div>

      <el-alert
        v-if="assignedToOther"
        type="warning"
        :closable="false"
        title="该工单已由其他客服接管，当前仅可查看。"
        class="handoff-notice"
      />
      <el-alert
        v-if="deliveryError"
        type="error"
        :closable="true"
        title="渠道回复发送失败，请检查连接状态"
        @close="deliveryError=''"
        class="handoff-notice"
      />

      <div class="handoff-actions">
        <el-button
          v-if="!ticket.assigneeId"
          type="primary"
          :icon="UserFilled"
          :loading="claiming"
          @click="claimTicket"
        >接单</el-button>
        <el-button
          v-if="ticket.status==='processing' && !assignedToOther"
          type="success"
          :icon="CircleCheck"
          @click="resolveTicket"
        >解决会话</el-button>
      </div>

      <el-collapse class="handoff-details">
        <el-collapse-item title="转接摘要与处理记录" name="handoff">
          <div class="handoff-summary">{{ contentText(ticket.description, '暂无转接摘要') }}</div>
          <el-timeline v-if="ticketRecords.length" class="record-timeline">
            <el-timeline-item
              v-for="record in ticketRecords"
              :key="record.id"
              :timestamp="formatDateTime(record.createTime)"
              placement="top"
            >
              <strong>{{ recordActionText(record.action) }}</strong>
              <div class="record-content">{{ contentText(record.content, '系统处理记录') }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section v-if="showCsat" class="csat-panel">
      <div class="csat-title">请为本次服务评分</div>
      <el-rate v-model="csatScore" :max="5" show-text :texts="['很差','较差','一般','满意','非常满意']" />
      <el-input v-model="csatFeedback" placeholder="反馈意见（选填）" />
      <el-button type="primary" @click="submitCsat" :disabled="!csatScore">提交评价</el-button>
    </section>

    <el-collapse class="conversation-context-details">
      <el-collapse-item title="会话信息与标签（点击展开）" name="context">
        <el-descriptions :column="isMobile ? 1 : 3" border class="conversation-meta">
          <el-descriptions-item label="编号">{{ conversation?.id }}</el-descriptions-item>
          <el-descriptions-item label="渠道">{{ channelTypeText(conversation?.channelType) }}</el-descriptions-item>
          <el-descriptions-item label="客户名称">{{ contentText(conversation?.customerName, '未知客户') }}</el-descriptions-item>
          <el-descriptions-item label="客户用户名">{{ conversation?.channelType === 'playground' ? '内部试聊用户' : conversation?.channelUserId }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ conversation?.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType">{{ statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(conversation?.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="接管状态">{{ handoffStatusText(conversation?.handoffStatus) }}</el-descriptions-item>
          <el-descriptions-item label="人工客服">{{ agentText(conversation?.assignedAgentName, conversation?.assignedAgentId) }}</el-descriptions-item>
        </el-descriptions>

        <div class="tag-section">
          <div class="section-title">标签</div>
          <div class="tag-controls">
            <el-tag v-for="tag in tags" :key="tag" closable @close="removeTag(tag)" type="info">{{ contentText(tag, '会话标签') }}</el-tag>
            <el-input v-model="newTag" placeholder="输入标签" size="small" @keyup.enter="addTag" />
            <el-button size="small" type="primary" @click="addTag">添加</el-button>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <div v-if="messages.length" class="message-list">
      <div v-for="message in messages" :key="message.id" :class="messageClass(message.role)">
        <div class="message-header">
          <span class="message-identity">
            <strong>{{ roleText(message.role) }}</strong>
            <el-tag
              v-if="message.role==='human' && messageDelivery(message)"
              :type="deliveryTag(messageDelivery(message).status)"
              size="small"
              effect="plain"
            >{{ deliveryText(messageDelivery(message).status) }}</el-tag>
          </span>
          <span class="message-time">{{ formatDateTime(message.createTime) }}</span>
        </div>
        <div
          v-if="['image', 'mixed'].includes(message.contentType)"
          class="message-content message-image"
        >
          <div v-if="message.contentType === 'mixed'" class="message-mixed-text">
            {{ contentText(message.content, '系统消息') }}
          </div>
          <a
            v-if="message.mediaUrl"
            class="message-image-link"
            :href="message.mediaUrl"
            target="_blank"
            rel="noopener"
          >
            <img
              class="message-image-preview"
              :src="message.mediaUrl"
              :alt="imageFileName(message)"
            />
          </a>
          <div v-if="canReplyWithImage" class="message-image-actions">
            <el-button size="small" plain @click="quoteImage(message)">引用图片</el-button>
            <el-button size="small" plain @click="openImageEditor(message)">编辑图片</el-button>
          </div>
        </div>
        <div v-else class="message-content">{{ contentText(message.content, '系统消息') }}</div>
        <div v-if="messageDelivery(message)?.error" class="delivery-error">
          回复发送失败，请检查渠道连接
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无消息" />

    <div v-if="conversation?.status==='transferred' && ticket && !assignedToOther && !isTicketClosed" class="reply-composer">
      <div v-if="quotedImage" class="quoted-image-card">
        <img :src="quotedImage.previewUrl" :alt="quotedImage.name" />
        <div class="quoted-image-info">
          <span>已附加：{{ quotedImage.name }}</span>
          <el-button link type="danger" @click="clearQuotedImage">移除图片</el-button>
        </div>
      </div>
      <div v-if="!latestUnansweredCustomerMessage" class="reply-waiting-state">
        当前没有待回复的客户消息
      </div>
      <el-input
        v-model="humanReply"
        type="textarea"
        class="human-reply-input"
        :disabled="!latestUnansweredCustomerMessage"
        :rows="5"
        maxlength="4000"
        show-word-limit
        resize="vertical"
        placeholder="输入人工回复"
      />
      <div class="reply-actions">
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="sending"
          :disabled="(!humanReply.trim() && !quotedImage) || !latestUnansweredCustomerMessage"
          @click="sendHumanReply"
        >发送回复</el-button>
        <el-button
          v-if="isDingTalkConversation"
          type="primary"
          plain
          :icon="Picture"
          :loading="imageSending"
          :disabled="imageSending || sending || !latestUnansweredCustomerMessage"
          @click="imageInput?.click()"
        >{{ quotedImage ? '更换图片' : '添加图片' }}</el-button>
        <input
          ref="imageInput"
          class="image-input"
          type="file"
          accept="image/*"
          @change="attachHumanImage"
        />
      </div>
    </div>

    <el-dialog
      v-model="imageEditorVisible"
      title="编辑图片"
      width="min(820px, 94vw)"
      destroy-on-close
      @opened="renderEditorCanvas"
    >
      <div class="image-editor-canvas-wrap">
        <canvas
          ref="editorCanvas"
          class="image-editor-canvas"
          @pointerdown="editorPointerDown"
          @pointermove="editorPointerMove"
          @pointerup="editorPointerUp"
          @pointerleave="editorPointerUp"
        />
      </div>
      <div class="image-editor-toolbar">
        <el-radio-group v-model="editorTool" size="small">
          <el-radio-button label="draw">画笔标注</el-radio-button>
          <el-radio-button label="crop">选择区域</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="rotateEditorImage">旋转 90°</el-button>
        <el-button size="small" @click="applyMosaic" :disabled="!editorSelection">选区打码</el-button>
        <el-button size="small" @click="applyCrop" :disabled="!editorSelection">裁剪选区</el-button>
        <el-button size="small" @click="resetEditorImage">重置</el-button>
        <el-button type="primary" size="small" @click="finishImageEditing">完成并引用</el-button>
      </div>
      <div class="image-editor-hint">画笔用于标注；选择区域后可打码或裁剪。完成后会放入回复区，确认发送即可。</div>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Back, CircleCheck, CircleClose, EditPen, MagicStick,
  Picture, Promotion, Switch, UserFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/index.js'
import { createChatSessionId } from '../../utils/chatSession.js'
import { channelTypeText, contentText, formatDateTime, priorityText } from '../../utils/displayText.js'

const route = useRoute()
const conversation = ref(null)
const messages = ref([])
const tags = ref([])
const ticket = ref(null)
const ticketRecords = ref([])
const currentUser = ref(null)
const newTag = ref('')
const humanReply = ref('')
const deliveryError = ref('')
const aiDraft = ref('')
const aiLoading = ref(false)
const claiming = ref(false)
const sending = ref(false)
const imageSending = ref(false)
const imageInput = ref(null)
const quotedImage = ref(null)
const imageEditorVisible = ref(false)
const editorCanvas = ref(null)
const editorImage = ref(null)
const editorSource = ref(null)
const editorTool = ref('draw')
const editorSelection = ref(null)
const editorDrawing = ref(false)
const editorStartPoint = ref(null)
const chatFocusMode = ref(true)
const csatScore = ref(0)
const csatFeedback = ref('')
const showCsat = ref(false)
const now = ref(Date.now())
const isMobile = ref(window.matchMedia('(max-width: 720px)').matches)
let pollTimer = null

function updateMobileLayout() {
  isMobile.value = window.matchMedia('(max-width: 720px)').matches
}

const statusTagType = computed(() => ({
  active:'success', transferred:'warning', closed:'info'
}[conversation.value?.status] || 'info'))

const statusText = computed(() => ({
  active:'进行中', transferred:'已转接', closed:'已关闭'
}[conversation.value?.status] || conversation.value?.status))

const assignedToOther = computed(() => Boolean(
  ticket.value?.assigneeId && currentUser.value?.id
    && ticket.value.assigneeId !== currentUser.value.id
))

const isDingTalkConversation = computed(() =>
  String(conversation.value?.channelType || '').trim().toLowerCase() === 'dingtalk'
)

const canReplyWithImage = computed(() => Boolean(
  isDingTalkConversation.value
    && conversation.value?.status === 'transferred'
    && ticket.value
    && !assignedToOther.value
    && !isTicketClosed.value
))

const latestUnansweredCustomerMessage = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    const message = messages.value[index]
    if (message.role !== 'user') continue
    const answered = messages.value.slice(index + 1)
      .some(candidate => ['ai', 'assistant', 'human'].includes(candidate.role))
    return answered ? null : message
  }
  return null
})

const isTicketClosed = computed(() => ['resolved', 'closed'].includes(ticket.value?.status))

const slaText = computed(() => {
  if (!ticket.value?.slaDeadline) return '未设置响应时限'
  if (isTicketClosed.value) return '已结束'
  const remaining = new Date(ticket.value.slaDeadline).getTime() - now.value
  return remaining <= 0
    ? `已超时 ${durationText(Math.abs(remaining))}`
    : `剩余 ${durationText(remaining)}`
})

const slaClass = computed(() => {
  if (!ticket.value?.slaDeadline || isTicketClosed.value) return 'muted'
  const remaining = new Date(ticket.value.slaDeadline).getTime() - now.value
  if (remaining <= 0) return 'sla-overdue'
  if (remaining <= 10 * 60 * 1000) return 'sla-warning'
  return 'sla-normal'
})

async function loadConversation() {
  const id = route.params.id
  const [detailResponse, messageResponse, tagResponse, ticketResponse] = await Promise.all([
    request.get(`/admin/conversation/${id}/detail`),
    request.get(`/admin/conversation/${id}/messages`),
    request.get(`/admin/conversation/${id}/tags`),
    request.get(`/admin/ticket/conversation/${id}`)
  ])
  conversation.value = detailResponse.data
  messages.value = messageResponse.data || []
  tags.value = tagResponse.data || []
  ticket.value = ticketResponse.data || null
  if (ticket.value?.id) {
    const recordResponse = await request.get(`/admin/ticket/${ticket.value.id}/records`)
    ticketRecords.value = recordResponse.data || []
  } else {
    ticketRecords.value = []
  }
  now.value = Date.now()
}

async function loadCurrentUser() {
  const response = await request.get('/admin/user/info')
  currentUser.value = response.data
}

async function transfer() {
  const response = await request.post('/core/conversation/transfer', {
    conversationId: conversation.value.id
  })
  ElMessage.success('已转人工')
  if (response.data?.ticketId) ticket.value = { id: response.data.ticketId }
  await loadConversation()
}

async function claimTicket() {
  claiming.value = true
  try {
    await request.post(`/admin/ticket/${ticket.value.id}/claim`)
    ElMessage.success('工单已接管')
    await loadConversation()
  } finally {
    claiming.value = false
  }
}

async function sendHumanReply() {
  const content = humanReply.value.trim()
  const target = latestUnansweredCustomerMessage.value
  if ((!content && !quotedImage.value) || !target) return
  if (quotedImage.value) {
    try {
      const file = quotedImage.value.file || await fetchImageFile(quotedImage.value)
      await sendImageFile(file, content, target.id)
    } catch (error) {
      deliveryError.value = `图片读取失败：${error?.message || '未知错误'}`
    }
    return
  }
  await deliverHumanReply(ticket.value.id, content, target.id)
}

async function deliverHumanReply(ticketId, content, replyToMessageId) {
  sending.value = true
  deliveryError.value = ''
  try {
    const response = await request.post(`/admin/ticket/${ticketId}/reply`, {
      content,
      replyToMessageId
    })
    if (response.data?.delivered) {
      ElMessage.success(response.data.deliveryStatus === 'STORED' ? '回复已保存' : '回复已发送')
      humanReply.value = ''
    } else {
      deliveryError.value = `回复已保存，但渠道发送失败：${response.data?.error || '未知错误'}`
    }
    await loadConversation()
    return Boolean(response.data?.delivered)
  } finally {
    sending.value = false
  }
}

function attachHumanImage(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 10MB')
    return
  }
  clearQuotedImage()
  quotedImage.value = {
    file,
    previewUrl: URL.createObjectURL(file),
    name: file.name || 'image'
  }
}

async function sendImageFile(file, content, replyToMessageId) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 10MB')
    return
  }
  imageSending.value = true
  sending.value = true
  deliveryError.value = ''
  try {
    const formData = new FormData()
    formData.append('file', file)
    if (content) formData.append('content', content)
    formData.append('replyToMessageId', String(replyToMessageId))
    const response = await request.post(
      `/admin/ticket/${ticket.value.id}/reply-image`, formData)
    if (response.data?.delivered) {
      ElMessage.success(response.data.deliveryStatus === 'STORED' ? '图片已保存' : '图片已发送')
      humanReply.value = ''
    } else {
      deliveryError.value = `图片已保存，但渠道发送失败：${response.data?.error || '未知错误'}`
    }
    clearQuotedImage()
    await loadConversation()
  } finally {
    imageSending.value = false
    sending.value = false
  }
}

async function fetchImageFile(image) {
  const response = await fetch(image.url, { credentials: 'include' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  const blob = await response.blob()
  return new File([blob], image.name || 'quoted-image.png', {
    type: blob.type || 'image/png'
  })
}

function quoteImage(message) {
  if (!message?.mediaUrl) return
  clearQuotedImage()
  quotedImage.value = {
    url: message.mediaUrl,
    previewUrl: message.mediaUrl,
    name: imageFileName(message)
  }
  ElMessage.success('图片已引用到回复区')
}

function clearQuotedImage() {
  if (quotedImage.value?.previewUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(quotedImage.value.previewUrl)
  }
  quotedImage.value = null
}

function openImageEditor(message) {
  if (!message?.mediaUrl) return
  editorSource.value = {
    url: message.mediaUrl,
    name: imageFileName(message)
  }
  editorTool.value = 'draw'
  editorSelection.value = null
  imageEditorVisible.value = true
}

function loadEditorImage(sourceUrl) {
  const source = sourceUrl || editorSource.value?.url
  if (!source || !editorCanvas.value) return
  const image = new Image()
  image.crossOrigin = 'anonymous'
  image.onload = () => {
    editorImage.value = image
    editorSelection.value = null
    renderEditorCanvas()
  }
  image.onerror = () => ElMessage.error('图片加载失败，无法编辑')
  image.src = source
}

function renderEditorCanvas() {
  const canvas = editorCanvas.value
  const image = editorImage.value
  if (!canvas || !image) {
    if (editorSource.value?.url) loadEditorImage()
    return
  }
  canvas.width = image.naturalWidth || image.width
  canvas.height = image.naturalHeight || image.height
  const context = canvas.getContext('2d')
  context.clearRect(0, 0, canvas.width, canvas.height)
  context.drawImage(image, 0, 0, canvas.width, canvas.height)
  if (editorSelection.value) drawSelection(context, editorSelection.value)
}

function editorPoint(event) {
  const canvas = editorCanvas.value
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  return {
    x: Math.max(0, Math.min(canvas.width, (event.clientX - rect.left) * canvas.width / rect.width)),
    y: Math.max(0, Math.min(canvas.height, (event.clientY - rect.top) * canvas.height / rect.height))
  }
}

function editorPointerDown(event) {
  if (!editorCanvas.value || !editorImage.value) return
  editorDrawing.value = true
  editorStartPoint.value = editorPoint(event)
  editorSelection.value = editorTool.value === 'crop'
    ? { ...editorStartPoint.value, width: 0, height: 0 }
    : null
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function editorPointerMove(event) {
  if (!editorDrawing.value) return
  const point = editorPoint(event)
  if (!point || !editorStartPoint.value) return
  const canvas = editorCanvas.value
  const context = canvas.getContext('2d')
  if (editorTool.value === 'crop') {
    editorSelection.value = normalizeSelection(editorStartPoint.value, point)
    renderEditorCanvas()
    return
  }
  context.strokeStyle = '#f56c6c'
  context.lineWidth = Math.max(4, canvas.width / 180)
  context.lineCap = 'round'
  context.lineJoin = 'round'
  context.beginPath()
  context.moveTo(editorStartPoint.value.x, editorStartPoint.value.y)
  context.lineTo(point.x, point.y)
  context.stroke()
  editorStartPoint.value = point
}

function editorPointerUp(event) {
  editorDrawing.value = false
  editorStartPoint.value = null
  event.currentTarget.releasePointerCapture?.(event.pointerId)
}

function normalizeSelection(start, end) {
  return {
    x: Math.min(start.x, end.x),
    y: Math.min(start.y, end.y),
    width: Math.abs(end.x - start.x),
    height: Math.abs(end.y - start.y)
  }
}

function drawSelection(context, selection) {
  if (!selection || selection.width < 2 || selection.height < 2) return
  context.save()
  context.strokeStyle = '#409eff'
  context.lineWidth = Math.max(3, context.canvas.width / 300)
  context.setLineDash([12, 8])
  context.strokeRect(selection.x, selection.y, selection.width, selection.height)
  context.restore()
}

function selectedBounds() {
  const selection = editorSelection.value
  if (!selection || selection.width < 4 || selection.height < 4) return null
  return {
    x: Math.round(selection.x),
    y: Math.round(selection.y),
    width: Math.round(selection.width),
    height: Math.round(selection.height)
  }
}

function applyCrop() {
  const bounds = selectedBounds()
  const sourceCanvas = editorCanvas.value
  if (!bounds || !sourceCanvas) return
  const cropped = document.createElement('canvas')
  cropped.width = bounds.width
  cropped.height = bounds.height
  cropped.getContext('2d').drawImage(
    sourceCanvas,
    bounds.x, bounds.y, bounds.width, bounds.height,
    0, 0, bounds.width, bounds.height
  )
  setEditorImageFromCanvas(cropped)
}

function applyMosaic() {
  const bounds = selectedBounds()
  const canvas = editorCanvas.value
  if (!bounds || !canvas) return
  const context = canvas.getContext('2d')
  const blockSize = Math.max(8, Math.round(Math.min(bounds.width, bounds.height) / 18))
  const imageData = context.getImageData(bounds.x, bounds.y, bounds.width, bounds.height)
  for (let y = 0; y < bounds.height; y += blockSize) {
    for (let x = 0; x < bounds.width; x += blockSize) {
      const sampleX = Math.min(x, bounds.width - 1)
      const sampleY = Math.min(y, bounds.height - 1)
      const index = (sampleY * bounds.width + sampleX) * 4
      context.fillStyle = `rgb(${imageData.data[index]}, ${imageData.data[index + 1]}, ${imageData.data[index + 2]})`
      context.fillRect(bounds.x + x, bounds.y + y, blockSize, blockSize)
    }
  }
  editorSelection.value = null
}

function rotateEditorImage() {
  const sourceCanvas = editorCanvas.value
  if (!sourceCanvas) return
  const rotated = document.createElement('canvas')
  rotated.width = sourceCanvas.height
  rotated.height = sourceCanvas.width
  const context = rotated.getContext('2d')
  context.translate(rotated.width / 2, rotated.height / 2)
  context.rotate(Math.PI / 2)
  context.drawImage(sourceCanvas, -sourceCanvas.width / 2, -sourceCanvas.height / 2)
  setEditorImageFromCanvas(rotated)
}

function setEditorImageFromCanvas(canvas) {
  const image = new Image()
  image.onload = () => {
    editorImage.value = image
    editorSelection.value = null
    renderEditorCanvas()
  }
  image.src = canvas.toDataURL('image/png')
}

function resetEditorImage() {
  editorSelection.value = null
  loadEditorImage()
}

function finishImageEditing() {
  const canvas = editorCanvas.value
  if (!canvas || !editorSource.value) return
  canvas.toBlob(blob => {
    if (!blob) return
    const fileName = `edited-${editorSource.value.name || 'image.png'}`
    const file = new File([blob], fileName, { type: 'image/png' })
    clearQuotedImage()
    quotedImage.value = {
      file,
      name: fileName,
      previewUrl: URL.createObjectURL(blob)
    }
    imageEditorVisible.value = false
    ElMessage.success('图片编辑完成，已放入回复区')
  }, 'image/png')
}

async function resolveTicket() {
  try {
    const result = await ElMessageBox.prompt('填写处理结果', '解决会话', {
      confirmButtonText: '确认解决',
      cancelButtonText: '取消',
      inputValue: '问题已解决',
      inputValidator: value => value?.trim() ? true : '处理结果不能为空'
    })
    await request.post(`/admin/ticket/${ticket.value.id}/resolve`, {
      resolution: result.value.trim()
    })
    ElMessage.success('会话已解决')
    showCsat.value = true
    await loadConversation()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') return
  }
}

async function closeConversation() {
  await request.post('/core/conversation/close', { conversationId: conversation.value.id })
  ElMessage.success('会话已关闭')
  showCsat.value = true
  await loadConversation()
}

async function submitCsat() {
  await request.put(`/admin/conversation/${conversation.value.id}/csat`, {
    csatScore: csatScore.value,
    csatFeedback: csatFeedback.value
  })
  ElMessage.success('评价已提交')
  showCsat.value = false
}

async function getAiDraft() {
  aiLoading.value = true
  try {
    const lastMessage = messages.value.filter(message => message.role === 'user').pop()
    const response = await request.post('/admin/playground/chat', {
      text: '请根据以下客户问题生成一段客服回复草稿：' + (lastMessage?.content || ''),
      sessionId: createChatSessionId(`draft-${route.params.id}`)
    })
    aiDraft.value = response.data?.reply || ''
  } finally {
    aiLoading.value = false
  }
}

function adoptAiDraft() {
  humanReply.value = aiDraft.value
  aiDraft.value = ''
}

async function addTag() {
  if (!newTag.value.trim()) return
  await request.post(`/admin/conversation/${route.params.id}/tags`, { tagName: newTag.value.trim() })
  newTag.value = ''
  await loadConversation()
}

async function removeTag(tag) {
  await request.delete(`/admin/conversation/${route.params.id}/tags/${encodeURIComponent(tag)}`)
  await loadConversation()
}

function parseMetadata(message) {
  if (!message?.metadata) return null
  try {
    return typeof message.metadata === 'string' ? JSON.parse(message.metadata) : message.metadata
  } catch (error) {
    return null
  }
}

function messageDelivery(message) {
  const metadata = parseMetadata(message)
  if (!metadata?.deliveryStatus) return null
  return { status: metadata.deliveryStatus, error: metadata.deliveryError }
}

function imageFileName(message) {
  const metadata = parseMetadata(message)
  return metadata?.fileName || message?.content || '会话图片'
}

function messageClass(role) {
  return `message-item message-${role}`
}

function roleText(role) {
  return { user:'客户', ai:'智能助手', assistant:'智能助手', human:'人工客服', system:'系统' }[role] || '未知角色'
}

function agentText(name, id) {
  if (name && !/[A-Za-z]/.test(name)) return name
  return id ? `客服 ${id}` : '未分配'
}

function handoffStatusText(value) {
  return { NONE:'未转接', WAITING:'等待接单', PROCESSING:'人工处理中', RESOLVED:'已解决' }[value] || '未转接'
}

function ticketStatusText(value) {
  return { pending:'待处理', processing:'处理中', resolved:'已解决', closed:'已关闭' }[value] || '未知状态'
}

function ticketStatusTag(value) {
  return { pending:'warning', processing:'primary', resolved:'success', closed:'info' }[value] || 'info'
}

function priorityTag(value) {
  return { P0:'danger', P1:'warning', P2:'primary', P3:'info' }[value] || 'info'
}

function deliveryText(value) {
  return { SENT:'已发送', STORED:'已保存', FAILED:'发送失败' }[value] || '未知状态'
}

function deliveryTag(value) {
  return { SENT:'success', STORED:'primary', FAILED:'danger' }[value] || 'info'
}

function recordActionText(value) {
  return { CLAIM:'接单', REPLY:'人工回复', REPLY_FAILED:'回复发送失败', RESOLVE:'解决工单' }[value] || '系统操作'
}

function durationText(milliseconds) {
  const minutes = Math.max(0, Math.floor(milliseconds / 60000))
  if (minutes < 60) return `${minutes} 分钟`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

onMounted(async () => {
  window.addEventListener('resize', updateMobileLayout)
  await Promise.all([loadCurrentUser(), loadConversation()])
  pollTimer = setInterval(() => {
    loadConversation().catch(() => {})
  }, 5000)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateMobileLayout)
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.conversation-detail-card {
  height: calc(100vh - 100px);
  height: calc(100dvh - 100px);
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.conversation-detail-card :deep(.el-card__body) {
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.page-header,
.header-actions,
.handoff-header,
.handoff-title,
.handoff-meta,
.handoff-actions,
.reply-composer,
.tag-controls,
.message-header,
.message-identity { display:flex; align-items:center; gap:10px; }
.page-header,
.handoff-header,
.message-header { justify-content:space-between; }
.page-title { display:flex; align-items:center; gap:14px; min-width:0; flex-wrap:wrap; }
.customer-name-label { color:#606266; font-size:14px; font-weight:600; white-space:nowrap; }
.conversation-context-label { color:#909399; font-size:13px; white-space:nowrap; }
.header-actions,
.handoff-actions,
.tag-controls { flex-wrap:wrap; }
.draft-alert,
.handoff-panel,
.csat-panel,
.conversation-meta,
.tag-section { margin-bottom:18px; }
.conversation-context-details { flex:0 0 auto; margin-bottom:12px; }
.conversation-context-details .conversation-meta { margin-bottom:16px; }
.conversation-context-details .tag-section { margin-bottom:4px; }
.draft-content,
.handoff-summary,
.record-content { white-space:pre-wrap; line-height:1.6; }
.draft-content { margin:8px 0; }
.handoff-panel { padding:16px 0; border-top:1px solid #dcdfe6; border-bottom:1px solid #dcdfe6; }
.handoff-title { font-weight:700; }
.handoff-meta { color:#606266; }
.handoff-notice,
.handoff-actions,
.reply-composer,
.handoff-details { margin-top:12px; }
.reply-composer .el-textarea { min-width:220px; }
.reply-actions { display:flex; flex-direction:column; justify-content:flex-end; gap:10px; min-width:112px; }
.reply-actions .el-button { width:100%; margin-left:0; }
.image-input { display:none; }
.handoff-summary { padding:0 4px 12px; color:#606266; }
.record-timeline { margin-top:12px; }
.csat-panel { display:grid; gap:10px; justify-items:start; padding:16px; background:#f0f9eb; border:1px solid #c2e7b0; border-radius:6px; }
.csat-title,
.section-title { font-weight:700; }
.csat-panel .el-input { width:min(360px, 100%); }
.tag-section .section-title { margin-bottom:8px; }
.tag-controls .el-input { width:140px; max-width:100%; }
.message-list {
  flex:1 1 auto;
  min-height:300px;
  overflow-y:auto;
  padding:4px 8px 4px 0;
}
.reply-composer {
  display:grid;
  grid-template-columns:minmax(0, 1fr) auto;
  align-items:stretch;
  gap:12px;
  flex:0 0 auto;
  padding:12px 0 0;
  border-top:1px solid #dcdfe6;
  background:var(--el-bg-color, #fff);
}
.human-reply-input :deep(.el-textarea__inner) {
  min-height:128px !important;
}
.reply-waiting-state {
  grid-column:1 / -1;
  color:#909399;
  font-size:13px;
}
.chat-focus-mode .handoff-panel {
  display:grid;
  grid-template-columns:minmax(0, 1fr) auto;
  align-items:center;
  gap:10px 16px;
  padding:10px 0;
  margin-bottom:10px;
}
.chat-focus-mode .handoff-actions { margin-top:0; flex-wrap:nowrap; }
.chat-focus-mode .handoff-notice { grid-column:1 / -1; margin-top:0; }
.chat-focus-mode .handoff-details,
.chat-focus-mode .conversation-context-details { display:none; }
.chat-focus-mode .message-list { min-height:0; }
.message-item { margin:10px 0; padding:12px; border-radius:6px; max-width:80%; }
.message-user { background:#e8f4fd; margin-right:auto; }
.message-ai { background:#f0f0f0; margin-left:auto; }
.message-human { background:#edf8eb; border:1px solid #b3dfaa; margin-left:auto; }
.message-system { background:#fff3e0; margin:0 auto; text-align:center; font-size:12px; max-width:100%; }
.message-time { font-size:12px; color:#909399; }
.message-content { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.55; }
.message-image { color:#606266; font-style:italic; }
.message-mixed-text { margin-bottom:10px; color:inherit; font-style:normal; white-space:pre-wrap; }
.message-image-link { display:block; width:fit-content; max-width:100%; }
.message-image-preview {
  display:block;
  max-width:min(520px, 100%);
  max-height:480px;
  object-fit:contain;
  border:1px solid #dcdfe6;
  border-radius:4px;
  background:#fff;
  cursor:zoom-in;
}
.message-image-actions { display:flex; gap:8px; margin-top:8px; }
.quoted-image-card {
  grid-column:1 / -1;
  display:flex;
  align-items:center;
  gap:10px;
  padding:8px 10px;
  border:1px solid #b3d8ff;
  border-radius:6px;
  background:#f0f7ff;
}
.quoted-image-card img {
  width:56px;
  height:56px;
  object-fit:contain;
  border:1px solid #dcdfe6;
  border-radius:4px;
  background:#fff;
}
.quoted-image-info { display:flex; align-items:center; gap:10px; min-width:0; color:#409eff; font-size:13px; }
.quoted-image-info > span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.image-editor-canvas-wrap {
  display:flex;
  justify-content:center;
  max-height:62vh;
  overflow:auto;
  padding:8px;
  background:#f5f7fa;
  border:1px solid #dcdfe6;
  border-radius:6px;
}
.image-editor-canvas {
  display:block;
  max-width:100%;
  height:auto;
  touch-action:none;
  cursor:crosshair;
  background:#fff;
}
.image-editor-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-top:12px; }
.image-editor-hint { margin-top:8px; color:#909399; font-size:12px; line-height:1.5; }
.delivery-error { margin-top:6px; color:#d73737; font-size:12px; }
.muted { color:#909399; }
.sla-normal { color:#529b2e; }
.sla-warning { color:#b88230; font-weight:600; }
.sla-overdue { color:#d73737; font-weight:700; }
@media (max-width: 720px) {
  .page-header,
  .handoff-header { align-items:stretch; flex-direction:column; }
  .page-title { align-items:flex-start; flex-direction:column; gap:4px; }
  .reply-composer { grid-template-columns:1fr; }
  .reply-actions { flex-direction:row; }
  .quoted-image-card { align-items:flex-start; }
  .quoted-image-info { align-items:flex-start; flex-direction:column; gap:2px; }
  .chat-focus-mode .handoff-panel { grid-template-columns:1fr; }
  .chat-focus-mode .handoff-actions { flex-wrap:wrap; }
  .message-item { max-width:100%; }
}
@media (max-height: 900px) and (min-width: 721px) {
  .message-list { min-height:220px; }
  .human-reply-input :deep(.el-textarea__inner) { min-height:96px !important; }
}
</style>
