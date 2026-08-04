<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>工单管理</span>
        <el-tag v-if="overdueCount" type="danger" effect="dark">
          {{ overdueCount }} 个工单已超时
        </el-tag>
      </div>
    </template>

    <div class="ticket-filters">
      <el-select v-model="statusFilter" clearable placeholder="工单状态" @change="fetchTickets">
        <el-option label="待处理" value="pending" />
        <el-option label="处理中" value="processing" />
        <el-option label="已解决" value="resolved" />
        <el-option label="已关闭" value="closed" />
      </el-select>
      <el-checkbox v-model="mineOnly" @change="fetchTickets">仅看我的</el-checkbox>
      <el-button :icon="Refresh" :loading="loading" @click="fetchTickets">刷新</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="tickets"
      border
      stripe
      class="desktop-ticket-table"
      :row-class-name="ticketRowClass"
    >
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{row}">{{ ticketTitleText(row.title) }}</template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="90">
        <template #default="{ row }">
          <el-tag :type="priorityTag(row.priority)" size="small">{{ priorityText(row.priority) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="负责人" width="130">
        <template #default="{ row }">
          <span v-if="row.assigneeId">{{ assigneeText(row) }}</span>
          <span v-else class="muted">待接单</span>
        </template>
      </el-table-column>
      <el-table-column label="响应时限" width="135">
        <template #default="{ row }">
          <span v-if="row.slaDeadline" :class="slaClass(row.slaDeadline, row.status)">
            {{ slaText(row.slaDeadline, row.status) }}
          </span>
          <span v-else class="muted">未设置</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="190">
        <template #default="{row}">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="245" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button
              v-if="row.status==='pending' && !row.assigneeId"
              size="small"
              type="primary"
              :icon="UserFilled"
              @click="claimTicket(row)"
            >接单</el-button>
            <el-button size="small" :icon="ChatDotSquare" @click="openConversation(row)">
              {{ row.status==='resolved' || row.status==='closed' ? '查看' : '处理' }}
            </el-button>
            <el-button
              v-if="row.status==='processing'"
              size="small"
              type="success"
              :icon="CircleCheck"
              @click="resolveTicket(row)"
            >解决</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div v-loading="loading" class="mobile-ticket-list">
      <div
        v-for="ticket in tickets"
        :key="ticket.id"
        class="mobile-ticket-item"
        :class="ticketRowClass({ row: ticket })"
      >
        <div class="mobile-ticket-header">
          <div class="mobile-ticket-heading">
            <span class="mobile-ticket-id">#{{ ticket.id }}</span>
            <strong>{{ ticketTitleText(ticket.title) }}</strong>
          </div>
          <div class="mobile-ticket-tags">
            <el-tag :type="priorityTag(ticket.priority)" size="small">
              {{ priorityText(ticket.priority) }}
            </el-tag>
            <el-tag :type="statusTag(ticket.status)" size="small">
              {{ statusText(ticket.status) }}
            </el-tag>
          </div>
        </div>

        <dl class="mobile-ticket-meta">
          <div>
            <dt>负责人</dt>
            <dd>{{ ticket.assigneeId ? assigneeText(ticket) : '待接单' }}</dd>
          </div>
          <div>
            <dt>响应时限</dt>
            <dd v-if="ticket.slaDeadline" :class="slaClass(ticket.slaDeadline, ticket.status)">
              {{ slaText(ticket.slaDeadline, ticket.status) }}
            </dd>
            <dd v-else class="muted">未设置</dd>
          </div>
          <div class="mobile-ticket-created">
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(ticket.createTime) }}</dd>
          </div>
        </dl>

        <div class="row-actions mobile-ticket-actions">
          <el-button
            v-if="ticket.status==='pending' && !ticket.assigneeId"
            size="small"
            type="primary"
            :icon="UserFilled"
            @click="claimTicket(ticket)"
          >接单</el-button>
          <el-button size="small" :icon="ChatDotSquare" @click="openConversation(ticket)">
            {{ ticket.status==='resolved' || ticket.status==='closed' ? '查看' : '处理' }}
          </el-button>
          <el-button
            v-if="ticket.status==='processing'"
            size="small"
            type="success"
            :icon="CircleCheck"
            @click="resolveTicket(ticket)"
          >解决</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !tickets.length" description="暂无工单" />
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheck, ChatDotSquare, Refresh, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/index.js'
import { formatDateTime, localizedSystemText, priorityText } from '../../utils/displayText.js'

const router = useRouter()
const tickets = ref([])
const statusFilter = ref('')
const mineOnly = ref(false)
const loading = ref(false)
const now = ref(Date.now())
let pollTimer = null

const overdueCount = computed(() => tickets.value.filter(ticket =>
  !['resolved', 'closed'].includes(ticket.status)
    && ticket.slaDeadline
    && new Date(ticket.slaDeadline).getTime() < now.value
).length)

async function fetchTickets() {
  loading.value = true
  try {
    const params = { mine: mineOnly.value }
    if (statusFilter.value) params.status = statusFilter.value
    const response = await request.get('/admin/ticket/list', { params })
    tickets.value = response.data?.records || []
    now.value = Date.now()
  } finally {
    loading.value = false
  }
}

async function claimTicket(ticket) {
  await request.post(`/admin/ticket/${ticket.id}/claim`)
  ElMessage.success('工单已接管')
  await fetchTickets()
}

async function resolveTicket(ticket) {
  try {
    const result = await ElMessageBox.prompt('填写处理结果', '解决工单', {
      confirmButtonText: '确认解决',
      cancelButtonText: '取消',
      inputValue: '问题已解决',
      inputValidator: value => value?.trim() ? true : '处理结果不能为空'
    })
    await request.post(`/admin/ticket/${ticket.id}/resolve`, {
      resolution: result.value.trim()
    })
    ElMessage.success('工单已解决，智能客服将在用户下次咨询时恢复接待')
    await fetchTickets()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
  }
}

function openConversation(ticket) {
  router.push('/conversation/' + ticket.conversationId)
}

function priorityTag(priority) {
  return { P0: 'danger', P1: 'warning', P2: 'primary', P3: 'info' }[priority] || 'info'
}

function assigneeText(ticket) {
  if (ticket.assigneeName && !/[A-Za-z]/.test(ticket.assigneeName)) return ticket.assigneeName
  return `客服 ${ticket.assigneeId}`
}

function ticketTitleText(value) {
  const text = localizedSystemText(value, '人工处理工单').replace(/[：:·-]+$/g, '').trim()
  return text || '人工处理工单'
}

function statusTag(status) {
  return { pending: 'warning', processing: 'primary', resolved: 'success', closed: 'info' }[status] || 'info'
}

function statusText(status) {
  return { pending: '待处理', processing: '处理中', resolved: '已解决', closed: '已关闭' }[status] || '未知状态'
}

function slaText(deadline, status) {
  if (['resolved', 'closed'].includes(status)) return '已结束'
  const remaining = new Date(deadline).getTime() - now.value
  if (remaining <= 0) return `已超时 ${durationText(Math.abs(remaining))}`
  return `剩余 ${durationText(remaining)}`
}

function durationText(milliseconds) {
  const minutes = Math.max(0, Math.floor(milliseconds / 60000))
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}

function slaClass(deadline, status) {
  if (['resolved', 'closed'].includes(status)) return 'muted'
  const remaining = new Date(deadline).getTime() - now.value
  if (remaining <= 0) return 'sla-overdue'
  if (remaining <= 10 * 60 * 1000) return 'sla-warning'
  return 'sla-normal'
}

function ticketRowClass({ row }) {
  return row.slaDeadline
    && !['resolved', 'closed'].includes(row.status)
    && new Date(row.slaDeadline).getTime() < now.value
    ? 'ticket-overdue' : ''
}

onMounted(() => {
  fetchTickets()
  pollTimer = setInterval(fetchTickets, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.page-header,
.ticket-filters,
.row-actions { display:flex; align-items:center; gap:10px; }
.page-header { justify-content:space-between; }
.ticket-filters { flex-wrap:wrap; margin-bottom:12px; }
.ticket-filters .el-select { width:150px; max-width:100%; }
.row-actions { gap:6px; }
.mobile-ticket-list { display:none; }
.muted { color:#909399; }
.sla-normal { color:#529b2e; }
.sla-warning { color:#b88230; font-weight:600; }
.sla-overdue { color:#d73737; font-weight:700; }
:deep(.ticket-overdue td.el-table__cell) { background:#fff2f0 !important; }
@media (max-width: 720px) {
  .desktop-ticket-table { display:none; }
  .mobile-ticket-list { display:block; }
  .mobile-ticket-item { padding:14px 0; border-top:1px solid #ebeef5; }
  .mobile-ticket-item:last-child { border-bottom:1px solid #ebeef5; }
  .mobile-ticket-item.ticket-overdue { margin:0 -10px; padding:14px 10px; background:#fff2f0; }
  .mobile-ticket-header,
  .mobile-ticket-heading,
  .mobile-ticket-tags { display:flex; align-items:flex-start; gap:8px; }
  .mobile-ticket-header { justify-content:space-between; }
  .mobile-ticket-heading { min-width:0; }
  .mobile-ticket-heading strong { overflow-wrap:anywhere; }
  .mobile-ticket-id { flex:none; color:#909399; font-variant-numeric:tabular-nums; }
  .mobile-ticket-tags { flex:none; }
  .mobile-ticket-meta { display:grid; grid-template-columns:1fr 1fr; gap:10px 16px; margin:12px 0; }
  .mobile-ticket-meta div { min-width:0; }
  .mobile-ticket-meta dt { margin-bottom:3px; color:#909399; font-size:12px; }
  .mobile-ticket-meta dd { margin:0; overflow-wrap:anywhere; }
  .mobile-ticket-created { grid-column:1 / -1; }
  .mobile-ticket-actions { justify-content:flex-end; flex-wrap:wrap; }
}
</style>
