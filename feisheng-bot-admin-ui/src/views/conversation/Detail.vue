<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>会话详情 #{{ conversation?.id }}</span>
        <div class="header-actions">
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

      <div v-if="!assignedToOther && !isTicketClosed" class="reply-composer">
        <el-input
          v-model="humanReply"
          type="textarea"
          :rows="3"
          maxlength="4000"
          show-word-limit
          resize="vertical"
          placeholder="输入人工回复"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="sending"
          :disabled="!humanReply.trim()"
          @click="sendHumanReply"
        >发送回复</el-button>
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
        <div class="message-content">{{ contentText(message.content, '系统消息') }}</div>
        <div v-if="messageDelivery(message)?.error" class="delivery-error">
          回复发送失败，请检查渠道连接
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无消息" />
  </el-card>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Back, CircleCheck, CircleClose, EditPen, MagicStick,
  Promotion, Switch, UserFilled
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
  if (!content) return
  sending.value = true
  deliveryError.value = ''
  try {
    const response = await request.post(`/admin/ticket/${ticket.value.id}/reply`, { content })
    if (response.data?.delivered) {
      ElMessage.success(response.data.deliveryStatus === 'STORED' ? '回复已保存' : '回复已发送')
      humanReply.value = ''
    } else {
      deliveryError.value = `回复已保存，但渠道发送失败：${response.data?.error || '未知错误'}`
    }
    await loadConversation()
  } finally {
    sending.value = false
  }
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
.header-actions,
.handoff-actions,
.tag-controls { flex-wrap:wrap; }
.draft-alert,
.handoff-panel,
.csat-panel,
.conversation-meta,
.tag-section { margin-bottom:18px; }
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
.reply-composer { align-items:flex-end; }
.reply-composer .el-textarea { flex:1; min-width:220px; }
.handoff-summary { padding:0 4px 12px; color:#606266; }
.record-timeline { margin-top:12px; }
.csat-panel { display:grid; gap:10px; justify-items:start; padding:16px; background:#f0f9eb; border:1px solid #c2e7b0; border-radius:6px; }
.csat-title,
.section-title { font-weight:700; }
.csat-panel .el-input { width:min(360px, 100%); }
.tag-section .section-title { margin-bottom:8px; }
.tag-controls .el-input { width:140px; max-width:100%; }
.message-list { max-height:600px; overflow-y:auto; }
.message-item { margin:10px 0; padding:12px; border-radius:6px; max-width:80%; }
.message-user { background:#e8f4fd; margin-right:auto; }
.message-ai { background:#f0f0f0; margin-left:auto; }
.message-human { background:#edf8eb; border:1px solid #b3dfaa; margin-left:auto; }
.message-system { background:#fff3e0; margin:0 auto; text-align:center; font-size:12px; max-width:100%; }
.message-time { font-size:12px; color:#909399; }
.message-content { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.55; }
.delivery-error { margin-top:6px; color:#d73737; font-size:12px; }
.muted { color:#909399; }
.sla-normal { color:#529b2e; }
.sla-warning { color:#b88230; font-weight:600; }
.sla-overdue { color:#d73737; font-weight:700; }
@media (max-width: 720px) {
  .page-header,
  .handoff-header,
  .reply-composer { align-items:stretch; flex-direction:column; }
  .message-item { max-width:100%; }
}
</style>
