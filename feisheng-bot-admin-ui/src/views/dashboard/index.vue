<template>
  <section class="statistics-page">
    <header class="page-heading">
      <div>
        <h1>统计数据</h1>
        <p>{{ currentDateText }}</p>
      </div>
      <el-tooltip content="刷新统计数据" placement="bottom">
        <el-button
          :icon="Refresh"
          circle
          :loading="loading"
          aria-label="刷新统计数据"
          @click="loadStatistics"
        />
      </el-tooltip>
    </header>

    <div v-loading="loading" class="metric-grid">
      <article v-for="item in metricCards" :key="item.label" class="metric-panel">
        <div :class="['metric-icon', item.tone]">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div class="metric-content">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.note }}</small>
        </div>
      </article>
    </div>

    <div class="statistics-grid">
      <el-card shadow="never" class="status-panel">
        <template #header>
          <div class="panel-heading">
            <span>当前服务状态</span>
            <el-tag type="success" effect="plain">实时</el-tag>
          </div>
        </template>
        <dl class="status-list">
          <div>
            <dt>进行中会话</dt>
            <dd>{{ numberText(overview.activeCount) }}</dd>
          </div>
          <div>
            <dt>处理中工单</dt>
            <dd>{{ numberText(overview.processingTickets) }}</dd>
          </div>
          <div>
            <dt>累计已解决工单</dt>
            <dd>{{ numberText(overview.resolvedTickets) }}</dd>
          </div>
          <div>
            <dt>常见问题命中</dt>
            <dd>{{ numberText(overview.faqHitCount) }}</dd>
          </div>
        </dl>
      </el-card>

      <el-card shadow="never" class="trend-panel">
        <template #header>
          <div class="panel-heading">
            <span>近 7 日业务数据</span>
            <span class="record-count">{{ dailyStats.length }} 天</span>
          </div>
        </template>
        <el-table v-if="dailyStats.length" :data="dailyStats" size="small" class="trend-table">
          <el-table-column prop="statDate" label="日期" min-width="110">
            <template #default="{ row }">{{ statDateText(row.statDate) }}</template>
          </el-table-column>
          <el-table-column prop="conversationCount" label="会话" min-width="74" />
          <el-table-column prop="messageCount" label="消息" min-width="74" />
          <el-table-column prop="faqHitCount" label="命中" min-width="74" />
          <el-table-column prop="transferCount" label="转人工" min-width="82" />
        </el-table>
        <el-empty v-else-if="!loading" description="暂无统计记录" :image-size="72" />
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { computed, markRaw, onMounted, ref } from 'vue'
import {
  ChatDotRound,
  CircleCheckFilled,
  Message,
  Refresh,
  Tickets,
  UserFilled
} from '@element-plus/icons-vue'
import request from '../../api/index.js'

const loading = ref(false)
const overview = ref({})
const dailyStats = ref([])

const currentDateText = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
}).format(new Date())

const metricCards = computed(() => [
  {
    label: '累计会话',
    value: numberText(overview.value.conversationCount),
    note: `今日新增 ${numberText(overview.value.todayConversations)}`,
    icon: markRaw(ChatDotRound),
    tone: 'blue'
  },
  {
    label: '今日消息',
    value: numberText(overview.value.todayMessages),
    note: `进行中 ${numberText(overview.value.activeCount)} 个会话`,
    icon: markRaw(Message),
    tone: 'green'
  },
  {
    label: '客户总数',
    value: numberText(overview.value.customerCount),
    note: '已同步渠道客户档案',
    icon: markRaw(UserFilled),
    tone: 'cyan'
  },
  {
    label: '待处理工单',
    value: numberText(overview.value.pendingTickets),
    note: `工单总数 ${numberText(overview.value.ticketCount)}`,
    icon: markRaw(Tickets),
    tone: 'amber'
  },
  {
    label: '工单解决率',
    value: `${numberText(overview.value.resolutionRate)}%`,
    note: `已解决 ${numberText(overview.value.resolvedTickets)}`,
    icon: markRaw(CircleCheckFilled),
    tone: 'red'
  }
])

async function loadStatistics() {
  loading.value = true
  try {
    const [overviewResponse, dailyResponse] = await Promise.all([
      request.get('/admin/statistics/overview'),
      request.get('/admin/statistics/daily', { params: { p: 1, s: 7 } })
    ])
    overview.value = overviewResponse.data || {}
    dailyStats.value = dailyResponse.data?.records || []
  } finally {
    loading.value = false
  }
}

function numberText(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : '0'
}

function statDateText(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

onMounted(loadStatistics)
</script>

<style scoped>
.statistics-page { min-width:0; }
.page-heading,
.panel-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.page-heading { margin-bottom:18px; }
.page-heading h1 { margin:0; color:#1f2937; font-size:22px; line-height:1.3; }
.page-heading p { margin:4px 0 0; color:#7b8494; font-size:13px; }
.metric-grid { display:grid; grid-template-columns:repeat(5, minmax(150px, 1fr)); gap:14px; min-height:128px; }
.metric-panel { display:flex; align-items:center; min-width:0; min-height:126px; padding:20px; border:1px solid #e2e7ef; border-radius:6px; background:#fff; }
.metric-icon { display:flex; flex:0 0 44px; align-items:center; justify-content:center; width:44px; height:44px; margin-right:14px; border-radius:6px; font-size:22px; }
.metric-icon.blue { color:#2563eb; background:#eaf1ff; }
.metric-icon.green { color:#198754; background:#e8f6ee; }
.metric-icon.cyan { color:#087f8c; background:#e5f6f7; }
.metric-icon.amber { color:#b26a00; background:#fff3dc; }
.metric-icon.red { color:#c43d3d; background:#fdecec; }
.metric-content { display:flex; min-width:0; flex-direction:column; }
.metric-content span { color:#697386; font-size:13px; }
.metric-content strong { margin:5px 0 3px; color:#202938; font-size:28px; line-height:1.15; font-variant-numeric:tabular-nums; }
.metric-content small { overflow:hidden; color:#8a94a6; font-size:12px; text-overflow:ellipsis; white-space:nowrap; }
.statistics-grid { display:grid; grid-template-columns:minmax(260px, 0.7fr) minmax(480px, 1.3fr); gap:14px; margin-top:14px; }
.statistics-grid :deep(.el-card) { border-radius:6px; }
.panel-heading { font-weight:600; }
.record-count { color:#909399; font-size:12px; font-weight:400; }
.status-list { margin:0; }
.status-list div { display:flex; align-items:center; justify-content:space-between; min-height:50px; border-bottom:1px solid #edf0f5; }
.status-list div:last-child { border-bottom:0; }
.status-list dt { color:#606b7d; }
.status-list dd { margin:0; color:#202938; font-size:18px; font-weight:700; font-variant-numeric:tabular-nums; }
.trend-table { width:100%; }
.trend-panel :deep(.el-empty) { padding:12px 0; }
@media (max-width: 1180px) {
  .metric-grid { grid-template-columns:repeat(3, minmax(180px, 1fr)); }
}
@media (max-width: 760px) {
  .page-heading { margin:4px 2px 14px; }
  .metric-grid { grid-template-columns:repeat(2, minmax(0, 1fr)); gap:10px; }
  .metric-panel { align-items:flex-start; min-height:132px; padding:14px; flex-direction:column; }
  .metric-panel:last-child { grid-column:1 / -1; }
  .metric-icon { width:38px; height:38px; margin:0 0 10px; flex-basis:38px; font-size:19px; }
  .metric-content strong { font-size:24px; }
  .statistics-grid { grid-template-columns:minmax(0, 1fr); gap:10px; margin-top:10px; }
  .trend-panel { overflow:hidden; }
}
</style>
