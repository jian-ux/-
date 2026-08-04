<template>
  <el-card>
    <template #header>
    <div style="display:flex;justify-content:space-between;align-items:center">
      <span>对话监控</span>
      <span v-if="newTransferCount>0" style="display:flex;align-items:center;gap:6px">
        <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#f56c6c;animation:pulse 1s infinite"></span>
        <span style="color:#f56c6c;font-weight:bold;font-size:13px">{{ newTransferCount }} 个新转人工</span>
        <el-button size="small" type="primary" plain @click="clearNotification">已查看</el-button>
      </span>
    </div>
  </template>
    <el-table :data="conversations" border stripe :row-style="rowStyle">
      <el-table-column prop="id" label="编号" :width="isMobile ? 58 : 68" />
      <el-table-column v-if="!isMobile" prop="channelType" label="渠道" width="100">
        <template #default="{row}">{{ channelTypeText(row.channelType) }}</template>
      </el-table-column>
      <el-table-column prop="customerName" label="客户名称" :min-width="isMobile ? 155 : 170">
        <template #default="{row}">
          <div class="customer-cell">
            <strong>{{ localizedSystemText(row.customerName, '未知客户') }}</strong>
            <span>
              {{ channelTypeText(row.channelType) }}
              <template v-if="row.channelType !== 'playground' && row.channelUserId"> · {{ row.channelUserId }}</template>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="title" label="标题" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="!isMobile" prop="priority" label="优先级" width="90">
        <template #default="{row}">
          <el-tag :type="priorityTag(row.priority)" size="small">{{ priorityText(row.priority) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" label="响应时限" width="130">
        <template #default="{row}">
          <span v-if="row.slaDeadline" :style="{color: slaColor(row.slaDeadline)}">
            {{ slaText(row.slaDeadline) }}
          </span>
          <span v-else style="color:#ccc">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="status" label="状态" width="100">
        <template #default="{row}">
          <el-tag :type="row.status==='active'?'success':row.status==='transferred'?'warning':'info'" size="small">
            {{row.status==='active'?'进行中':row.status==='transferred'?'已转接':'已关闭'}}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!isMobile" prop="createTime" label="创建时间" width="180">
        <template #default="{row}">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="isMobile ? 90 : 120" :fixed="isMobile ? false : 'right'">
        <template #default="{row}">
          <el-button size="small" @click="showDetail(row)">详情</el-button>
          <el-dropdown v-if="row.status==='active'" @command="(cmd) => changePriority(row, cmd)">
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
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '../../api/index.js'
import { ElMessage } from 'element-plus'
import { channelTypeText, formatDateTime, localizedSystemText, priorityText } from '../../utils/displayText.js'
const router = useRouter()
const conversations = ref([])
const isMobile = ref(window.matchMedia('(max-width: 720px)').matches)
const prevTransferredCount = ref(0)
const newTransferCount = ref(0)
let pollTimer = null

async function fetch() {
  try {
    const r = await request.get('/admin/conversation/list')
    const list = r.data?.records||[]
    const transferred = list.filter(c => c.status==='transferred').length
    if (prevTransferredCount.value > 0 && transferred > prevTransferredCount.value) {
      newTransferCount.value += transferred - prevTransferredCount.value
      playNotificationSound()
    }
    conversations.value = list
    prevTransferredCount.value = transferred
  } catch(e) { conversations.value = [] }
}

function rowStyle({ row }) {
  if (row.status === 'transferred') return { background: '#fff7e6', cursor: 'pointer' }
  return {}
}

function playNotificationSound() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.value = 880
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.25, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.4)
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + 0.4)
  } catch(e) {}
}

function clearNotification() { newTransferCount.value = 0 }

function priorityTag(p) {
  return { P0: 'danger', P1: 'warning', P2: 'primary', P3: 'info' }[p] || 'primary'
}

function slaColor(deadline) {
  const remain = new Date(deadline) - Date.now()
  if (remain < 0) return '#f56c6c'
  if (remain < 10 * 60 * 1000) return '#e6a23c'
  return '#67c23a'
}

function slaText(deadline) {
  const remain = new Date(deadline) - Date.now()
  if (remain < 0) return '已超时'
  const min = Math.floor(remain / 60000)
  if (min < 60) return min + '分钟'
  const hours = Math.floor(min / 60)
  return hours + '小时' + (min % 60) + '分'
}

async function changePriority(row, priority) {
  try {
    await request.put('/admin/conversation/' + row.id + '/priority', { priority })
    row.priority = priority
    ElMessage.success('优先级已更新')
    fetch()
  } catch(e) {}
}

function showDetail(row) { router.push('/conversation/' + row.id) }
function updateMobileLayout() {
  isMobile.value = window.matchMedia('(max-width: 720px)').matches
}
onMounted(() => {
  window.addEventListener('resize', updateMobileLayout)
  fetch()
  pollTimer = setInterval(fetch, 10000)
})
onUnmounted(() => {
  window.removeEventListener('resize', updateMobileLayout)
  if (pollTimer) clearInterval(pollTimer)
})
</script>
<style scoped>
.customer-cell { display:flex; min-width:0; flex-direction:column; line-height:1.35; }
.customer-cell strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.customer-cell span { color:#909399; font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
@keyframes pulse { 0%,100% { opacity:1 } 50% { opacity:0.3 } }
</style>
