<template>
  <el-card class="customer-page">
    <template #header>
      <div class="page-header">
        <span>客户管理</span>
        <el-button :icon="Refresh" :loading="syncing" @click="syncProfiles">同步客户资料</el-button>
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
        v-model="keyword"
        class="keyword-filter"
        clearable
        placeholder="姓名、昵称、手机、备注或渠道用户标识"
        @clear="search"
        @keyup.enter="search"
      />
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="customers"
      border
      stripe
      class="desktop-customer-table"
      @row-click="goDetail"
    >
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column label="客户" min-width="210">
        <template #default="{ row }">
          <div class="customer-identity">
            <el-avatar :size="36" :src="row.avatar || undefined">
              <el-icon><UserFilled /></el-icon>
            </el-avatar>
            <div>
              <strong>{{ customerDisplayName(row) }}</strong>
              <span>{{ customerSecondaryText(row) }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机" min-width="125">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column prop="channelType" label="渠道" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ channelTypeText(row.channelType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalConversations" label="会话数" width="82" align="right" />
      <el-table-column prop="lastContactTime" label="最后联系" width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastContactTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="82" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click.stop="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-loading="loading" class="mobile-customer-list">
      <article v-for="customer in customers" :key="customer.id" class="mobile-customer-item">
        <div class="customer-identity">
          <el-avatar :size="42" :src="customer.avatar || undefined">
            <el-icon><UserFilled /></el-icon>
          </el-avatar>
          <div>
            <strong>{{ customerDisplayName(customer) }}</strong>
            <span>{{ customerSecondaryText(customer) }}</span>
          </div>
          <el-tag size="small" effect="plain">{{ channelTypeText(customer.channelType) }}</el-tag>
        </div>
        <dl class="customer-meta">
          <div><dt>手机</dt><dd>{{ customer.phone || '-' }}</dd></div>
          <div><dt>会话数</dt><dd>{{ customer.totalConversations || 0 }}</dd></div>
          <div class="full-row"><dt>备注</dt><dd>{{ customer.remark || '-' }}</dd></div>
          <div class="full-row"><dt>最后联系</dt><dd>{{ formatDateTime(customer.lastContactTime) }}</dd></div>
        </dl>
        <el-button class="detail-button" size="small" @click="goDetail(customer)">查看详情</el-button>
      </article>
      <el-empty v-if="!loading && !customers.length" description="暂无客户资料" />
    </div>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        :layout="isMobile ? 'prev, pager, next' : 'total, prev, pager, next, jumper'"
        @current-change="fetchCustomers"
      />
    </div>
  </el-card>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../../api/index.js'
import { loadChannelOptions } from '../../utils/channelOptions.js'
import { channelTypeText, formatDateTime } from '../../utils/displayText.js'

const router = useRouter()
const customers = ref([])
const channelOptions = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const keyword = ref('')
const channelType = ref('')
const loading = ref(false)
const syncing = ref(false)
const isMobile = ref(window.matchMedia('(max-width: 720px)').matches)

async function fetchCustomers() {
  loading.value = true
  try {
    const params = { page: page.value, size: pageSize }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (channelType.value) params.channelType = channelType.value
    const response = await request.get('/admin/customer/list', { params })
    customers.value = response.data?.records || []
    total.value = response.data?.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  fetchCustomers()
}

function resetFilters() {
  keyword.value = ''
  channelType.value = ''
  search()
}

async function syncProfiles() {
  syncing.value = true
  try {
    const response = await request.post('/admin/customer/sync')
    const affected = Number(response.data?.affectedRows || 0)
    ElMessage.success(affected > 0 ? `客户资料同步完成，更新 ${affected} 条记录` : '客户资料已是最新')
    page.value = 1
    await fetchCustomers()
  } catch {
    // The shared request interceptor displays the server error.
  } finally {
    syncing.value = false
  }
}

function customerDisplayName(customer) {
  return String(customer.name || customer.nickname || customer.channelUserId || '未知客户').trim()
}

function customerSecondaryText(customer) {
  const nickname = String(customer.nickname || '').trim()
  const channelUserId = String(customer.channelUserId || '').trim()
  if (nickname && nickname !== customerDisplayName(customer)) {
    return channelUserId ? `${nickname} · ${channelUserId}` : nickname
  }
  return channelUserId || '暂无渠道用户标识'
}

function goDetail(customer) {
  router.push(`/customer/${customer.id}`)
}

function updateMobileLayout() {
  isMobile.value = window.matchMedia('(max-width: 720px)').matches
}

onMounted(async () => {
  window.addEventListener('resize', updateMobileLayout)
  const channelRequest = loadChannelOptions(request)
    .then(options => { channelOptions.value = options })
    .catch(() => { channelOptions.value = [] })
  await Promise.all([fetchCustomers(), channelRequest])
})

onUnmounted(() => window.removeEventListener('resize', updateMobileLayout))
</script>

<style scoped>
.page-header,
.filters,
.customer-identity { display:flex; align-items:center; gap:10px; }
.page-header { justify-content:space-between; font-weight:600; }
.filters { flex-wrap:wrap; margin-bottom:14px; }
.channel-filter { width:210px; }
.keyword-filter { width:300px; }
.customer-identity { min-width:0; }
.customer-identity > div { display:flex; min-width:0; flex:1; flex-direction:column; line-height:1.4; }
.customer-identity strong,
.customer-identity span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.customer-identity span { color:#909399; font-size:12px; }
.mobile-customer-list { display:none; }
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
@media (max-width: 720px) {
  .customer-page :deep(.el-card__header) { padding:14px 12px; }
  .customer-page :deep(.el-card__body) { padding:12px; }
  .page-header { flex-wrap:wrap; }
  .filters { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); }
  .channel-filter,
  .keyword-filter { width:100%; }
  .keyword-filter { grid-column:1 / -1; }
  .filters .el-button { width:100%; margin:0; }
  .desktop-customer-table { display:none; }
  .mobile-customer-list { display:block; min-height:80px; }
  .mobile-customer-item { padding:16px 0; border-top:1px solid #ebeef5; }
  .mobile-customer-item:last-child { border-bottom:1px solid #ebeef5; }
  .customer-meta { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin:14px 0; }
  .customer-meta dt { margin-bottom:3px; color:#909399; font-size:12px; }
  .customer-meta dd { margin:0; overflow-wrap:anywhere; }
  .customer-meta .full-row { grid-column:1 / -1; }
  .detail-button { display:block; margin-left:auto; }
  .pagination-wrap { justify-content:center; }
}
</style>
