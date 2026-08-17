<template>
  <el-card class="conversation-page">
    <template #header>
      <div class="page-header">
        <span>对话监控</span>
        <span v-if="newTransferCount > 0" class="transfer-notice">
          <span class="notice-dot" />
          <span>{{ newTransferCount }} 个新转人工</span>
          <el-button size="small" type="primary" plain @click="clearNotification">已查看</el-button>
        </span>
      </div>
    </template>

    <div class="filters">
      <el-select
        v-model="channelType"
        class="channel-filter"
        filterable
        clearable
        placeholder="渠道名称"
        @change="search"
      >
        <el-option
          v-for="channel in channelOptions"
          :key="channel.value"
          :label="channel.label"
          :value="channel.value"
        />
      </el-select>
      <el-input
        v-model="customerName"
        class="customer-filter"
        clearable
        placeholder="客户名称或渠道用户标识"
        @clear="search"
        @keyup.enter="search"
      />
      <el-select v-model="status" class="status-filter" clearable placeholder="会话状态" @change="search">
        <el-option label="进行中" value="active" />
        <el-option label="已转人工" value="transferred" />
        <el-option label="已关闭" value="closed" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="conversations"
      border
      stripe
      :row-style="rowStyle"
    >
      <el-table-column prop="id" label="编号" :width="isMobile ? 58 : 68" />
      <el-table-column v-if="!isMobile" prop="channelName" label="渠道名称" min-width="135">
        <template #default="{ row }">
          <span>{{ channelNameText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="customerName" label="客户名称" :min-width="isMobile ? 170 : 160">
        <template #default="{ row }">
          <div class="customer-cell">
            <strong>{{ row.customerName || row.channelUserId || '未知客户' }}</strong>
            <span>
              {{ channelNameText(row) }}
              <template v-if="row.channelUserId"> · {{ row.channelUserId }}</template>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="title" label="标题" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="!isMobile" prop="priority" label="优先级" width="90">
        <template #default="{ row }">
          <el-tag :type="priorityTag(row.priority)" size="small">{{ priorityText(row.priority) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" label="响应时限" width="130">
        <template #default="{ row }">
          <span v-if="row.slaDeadline" :style="{ color: slaColor(row.slaDeadline) }">
            {{ slaText(row.slaDeadline) }}
          </span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="createTime" label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="isMobile ? 84 : 158" :fixed="isMobile ? false : 'right'">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" @click="showDetail(row)">详情</el-button>
            <el-dropdown v-if="!isMobile && row.status === 'active'" @command="command => changePriority(row, command)">
              <el-button size="small">优先级</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="P0">紧急</el-dropdown-item>
                  <el-dropdown-item command="P1">高</el-dropdown-item>
                  <el-dropdown-item command="P2">中</el-dropdown-item>
                  <el-dropdown-item command="P3">低</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        :layout="isMobile ? 'prev, pager, next' : 'total, prev, pager, next, jumper'"
        @current-change="fetchConversations"
      />
    </div>
  </el-card>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../../api/index.js'
import { loadChannelOptions } from '../../utils/channelOptions.js'
import { channelNameText, formatDateTime, priorityText } from '../../utils/displayText.js'

const router = useRouter()
const conversations = ref([])
const channelOptions = ref([])
const channelType = ref('')
const customerName = ref('')
const status = ref('')
const page = ref(1)
const pageSize = 10
const total = ref(0)
const loading = ref(false)
const isMobile = ref(window.matchMedia('(max-width: 720px)').matches)
const prevTransferredCount = ref(0)
const newTransferCount = ref(0)
let pollTimer = null

async function fetchConversations(silent = false) {
  if (!silent) loading.value = true
  try {
    const params = { page: page.value, size: pageSize }
    if (channelType.value) params.channelType = channelType.value
    if (customerName.value.trim()) params.customerName = customerName.value.trim()
    if (status.value) params.status = status.value
    const response = await request.get('/admin/conversation/list', { params })
    const list = response.data?.records || []
    const transferred = list.filter(conversation => conversation.status === 'transferred').length
    if (prevTransferredCount.value > 0 && transferred > prevTransferredCount.value) {
      newTransferCount.value += transferred - prevTransferredCount.value
      playNotificationSound()
    }
    conversations.value = list
    total.value = response.data?.total || 0
    prevTransferredCount.value = transferred
  } finally {
    if (!silent) loading.value = false
  }
}

function search() {
  page.value = 1
  fetchConversations()
}

function resetFilters() {
  channelType.value = ''
  customerName.value = ''
  status.value = ''
  search()
}

function rowStyle({ row }) {
  if (row.status === 'transferred') return { background: '#fff7e6', cursor: 'pointer' }
  return {}
}

function playNotificationSound() {
  try {
    const context = new (window.AudioContext || window.webkitAudioContext)()
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    oscillator.connect(gain)
    gain.connect(context.destination)
    oscillator.frequency.value = 880
    oscillator.type = 'sine'
    gain.gain.setValueAtTime(0.25, context.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.01, context.currentTime + 0.4)
    oscillator.start(context.currentTime)
    oscillator.stop(context.currentTime + 0.4)
  } catch {
    // Browser audio can be unavailable before the first user interaction.
  }
}

function clearNotification() {
  newTransferCount.value = 0
}

function priorityTag(priority) {
  return { P0: 'danger', P1: 'warning', P2: 'primary', P3: 'info' }[priority] || 'primary'
}

function statusTag(value) {
  return { active: 'success', transferred: 'warning', closed: 'info' }[value] || 'info'
}

function statusText(value) {
  return { active: '进行中', transferred: '已转人工', closed: '已关闭' }[value] || '未知状态'
}

function slaColor(deadline) {
  const remaining = new Date(deadline) - Date.now()
  if (remaining < 0) return '#d73737'
  if (remaining < 10 * 60 * 1000) return '#b88230'
  return '#529b2e'
}

function slaText(deadline) {
  const remaining = new Date(deadline) - Date.now()
  if (remaining < 0) return '已超时'
  const minutes = Math.floor(remaining / 60000)
  if (minutes < 60) return `${minutes} 分钟`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

async function changePriority(row, priority) {
  await request.put(`/admin/conversation/${row.id}/priority`, { priority })
  ElMessage.success('优先级已更新')
  await fetchConversations()
}

function showDetail(row) {
  router.push(`/conversation/${row.id}`)
}

function updateMobileLayout() {
  isMobile.value = window.matchMedia('(max-width: 720px)').matches
}

onMounted(async () => {
  window.addEventListener('resize', updateMobileLayout)
  const channelRequest = loadChannelOptions(request)
    .then(options => { channelOptions.value = options })
    .catch(() => { channelOptions.value = [] })
  await Promise.all([fetchConversations(), channelRequest])
  pollTimer = setInterval(() => fetchConversations(true), 10000)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateMobileLayout)
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.page-header,
.filters,
.transfer-notice,
.row-actions { display:flex; align-items:center; gap:8px; }
.page-header { justify-content:space-between; font-weight:600; }
.transfer-notice { color:#d73737; font-size:13px; }
.notice-dot { display:inline-block; width:8px; height:8px; border-radius:50%; background:#d73737; animation:pulse 1s infinite; }
.filters { flex-wrap:wrap; margin-bottom:14px; }
.channel-filter { width:210px; }
.customer-filter { width:250px; }
.status-filter { width:150px; }
.customer-cell { display:flex; min-width:0; flex-direction:column; line-height:1.4; }
.customer-cell strong,
.customer-cell span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.customer-cell span { color:#909399; font-size:12px; }
.row-actions { gap:6px; }
.muted { color:#909399; }
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
@keyframes pulse { 0%, 100% { opacity:1; } 50% { opacity:0.3; } }
@media (max-width: 720px) {
  .conversation-page :deep(.el-card__header) { padding:14px 12px; }
  .conversation-page :deep(.el-card__body) { padding:12px; }
  .page-header { align-items:flex-start; flex-direction:column; }
  .filters { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); }
  .channel-filter,
  .customer-filter,
  .status-filter { width:100%; }
  .customer-filter { grid-column:1 / -1; }
  .filters .el-button { width:100%; margin:0; }
  .pagination-wrap { justify-content:center; }
}
</style>
