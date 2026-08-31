<template>
  <el-card v-if="customer">
    <template #header>
      <div class="detail-header">
        <span>客户详情 #{{ customer.id }}</span>
        <div class="header-actions">
          <el-button type="primary" @click="openEdit">编辑资料</el-button>
          <el-button @click="router.push('/customer')">返回列表</el-button>
        </div>
      </div>
    </template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="昵称">{{ localizedSystemText(customer.nickname, '客户昵称') }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ localizedSystemText(customer.name, '客户姓名') }}</el-descriptions-item>
      <el-descriptions-item label="手机">{{ customer.phone || '-' }}</el-descriptions-item>
      <el-descriptions-item label="邮箱">{{ customer.email || '-' }}</el-descriptions-item>
      <el-descriptions-item label="渠道">{{ channelTypeText(customer.channelType) }}</el-descriptions-item>
      <el-descriptions-item label="客户用户名">{{ customer.channelUserId || '-' }}</el-descriptions-item>
      <el-descriptions-item label="会话数">{{ customer.totalConversations || 0 }}</el-descriptions-item>
      <el-descriptions-item label="最后联系">{{ formatDateTime(customer.lastContactTime) }}</el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ formatDateTime(customer.createTime) }}</el-descriptions-item>
      <el-descriptions-item label="备注" :span="2">{{ customer.remark || '-' }}</el-descriptions-item>
    </el-descriptions>

    <section class="long-term-section">
      <h3>长期上下文</h3>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="客户画像">
          <pre class="profile-text">{{ profileText }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="长期摘要">
          <div class="summary-text">{{ customer.longTermSummary || '暂无长期摘要' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </section>

    <section class="conversation-section">
      <h3>历史会话</h3>
      <el-table :data="conversations" border stripe @row-click="goConversation">
        <el-table-column prop="id" label="编号" width="72" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{row}">{{ conversationStatusText(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="开始时间" width="180">
          <template #default="{row}">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="updateTime" label="最后更新" width="180">
          <template #default="{row}">{{ formatDateTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{row}">
            <el-button size="small" @click.stop="goConversation(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!conversations.length" description="暂无历史会话" />
      <el-pagination
        v-if="conversationTotal > conversationSize"
        class="conversation-pagination"
        layout="prev,pager,next"
        :total="conversationTotal"
        :page-size="conversationSize"
        :current-page="conversationPage"
        @current-change="changeConversationPage"
      />
    </section>

    <section class="timeline-section">
      <div class="section-heading">
        <h3>完整对话时间线</h3>
        <span class="section-hint">按时间合并该客户的全部会话</span>
      </div>
      <el-timeline v-loading="timelineLoading">
        <el-timeline-item
          v-for="item in timeline"
          :key="item.messageId"
          :timestamp="formatDateTime(item.createTime)"
          placement="top"
          :type="timelineType(item.role)"
        >
          <div class="timeline-item">
            <div class="timeline-meta">
              <span>{{ roleText(item.role) }}</span>
              <el-button link type="primary" @click="goTimelineConversation(item)">
                会话 #{{ item.conversationId }}
              </el-button>
              <span v-if="item.conversationTitle" class="timeline-title">{{ item.conversationTitle }}</span>
            </div>
            <div class="timeline-content" :class="{ 'system-content': item.role === 'system' }">
              {{ item.content || `[${item.contentType || '消息'}]` }}
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!timelineLoading && !timeline.length" description="暂无完整对话记录" />
      <el-pagination
        v-if="timelineTotal > timelineSize"
        class="timeline-pagination"
        layout="prev,pager,next"
        :total="timelineTotal"
        :page-size="timelineSize"
        :current-page="timelinePage"
        @current-change="changeTimelinePage"
      />
    </section>
  </el-card>
  <el-empty v-else description="客户不存在" />

  <el-dialog v-model="editVisible" title="编辑客户资料" width="min(500px, 92vw)">
    <el-form :model="form" label-width="80px">
      <el-form-item label="姓名"><el-input v-model="form.name" maxlength="100" /></el-form-item>
      <el-form-item label="手机"><el-input v-model="form.phone" maxlength="20" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="100" /></el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="4" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible=false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveCustomer">保存</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../../api/index.js'
import { channelTypeText, formatDateTime, localizedSystemText } from '../../utils/displayText.js'
const route = useRoute()
const router = useRouter()
const customer = ref(null)
const conversations = ref([])
const conversationTotal = ref(0)
const conversationPage = ref(1)
const conversationSize = 10
const timeline = ref([])
const timelineTotal = ref(0)
const timelinePage = ref(1)
const timelineSize = 50
const timelineLoading = ref(false)
const editVisible = ref(false)
const saving = ref(false)
const form = reactive({ name:'', phone:'', email:'', remark:'' })

async function loadCustomer() {
  const r = await request.get('/admin/customer/'+route.params.id)
  customer.value = r.data
}

async function loadConversations() {
  const r = await request.get(`/admin/customer/${route.params.id}/conversations`, {
    params: { page: conversationPage.value, size: conversationSize }
  })
  conversations.value = r.data?.records || []
  conversationTotal.value = r.data?.total || 0
}

async function loadTimeline() {
  timelineLoading.value = true
  try {
    const r = await request.get(`/admin/customer/${route.params.id}/timeline`, {
      params: { page: timelinePage.value, size: timelineSize }
    })
    timeline.value = r.data?.records || []
    timelineTotal.value = r.data?.total || 0
  } finally {
    timelineLoading.value = false
  }
}

const profileText = computed(() => {
  if (!customer.value?.profileJson) return '暂无已确认画像'
  try {
    const parsed = JSON.parse(customer.value.profileJson)
    return Object.entries(parsed)
      .map(([key, value]) => `${key}: ${value?.value || value}`)
      .join('\n') || '暂无已确认画像'
  } catch (_) {
    return customer.value.profileJson
  }
})

function roleText(role) {
  return { user: '客户', ai: '智能客服', human: '人工客服', system: '系统' }[role] || role || '消息'
}

function timelineType(role) {
  return { user: 'primary', ai: 'success', human: 'warning', system: 'info' }[role] || 'info'
}

function goTimelineConversation(item) {
  if (item?.conversationId) router.push(`/conversation/${item.conversationId}`)
}

function changeTimelinePage(page) {
  timelinePage.value = page
  loadTimeline()
}

function openEdit() {
  Object.assign(form, {
    name: customer.value?.name || '',
    phone: customer.value?.phone || '',
    email: customer.value?.email || '',
    remark: customer.value?.remark || ''
  })
  editVisible.value = true
}

async function saveCustomer() {
  saving.value = true
  try {
    await request.put(`/admin/customer/${route.params.id}`, {
      name: form.name.trim(),
      phone: form.phone.trim(),
      email: form.email.trim(),
      remark: form.remark.trim()
    })
    ElMessage.success('客户资料已更新')
    editVisible.value = false
    await loadCustomer()
  } finally {
    saving.value = false
  }
}

function changeConversationPage(page) {
  conversationPage.value = page
  loadConversations()
}

function goConversation(row) {
  router.push(`/conversation/${row.id}`)
}

function conversationStatusText(status) {
  return { active:'进行中', transferred:'已转接', closed:'已关闭' }[status] || status || '-'
}

onMounted(() => Promise.all([loadCustomer(), loadConversations(), loadTimeline()]))
</script>
<style scoped>
.detail-header,
.header-actions { display:flex; align-items:center; gap:8px; }
.detail-header { justify-content:space-between; }
.conversation-section { margin-top:24px; }
.conversation-section h3 { margin:0 0 12px; font-size:16px; }
.conversation-pagination { margin-top:16px; }
.long-term-section,
.timeline-section { margin-top:24px; }
.long-term-section h3,
.timeline-section h3 { margin:0 0 12px; font-size:16px; }
.profile-text { margin:0; white-space:pre-wrap; font:inherit; line-height:1.6; }
.summary-text { white-space:pre-wrap; line-height:1.6; }
.section-heading { display:flex; align-items:baseline; gap:10px; }
.section-hint { color:#909399; font-size:12px; }
.timeline-item { padding-bottom:4px; }
.timeline-meta { display:flex; align-items:center; gap:8px; color:#606266; font-size:13px; }
.timeline-title { color:#909399; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.timeline-content { margin-top:4px; white-space:pre-wrap; line-height:1.6; overflow-wrap:anywhere; }
.system-content { color:#909399; font-size:13px; }
.timeline-pagination { margin-top:16px; }
@media (max-width: 560px) {
  .detail-header { align-items:flex-start; flex-direction:column; }
  .header-actions { flex-wrap:wrap; }
}
</style>
