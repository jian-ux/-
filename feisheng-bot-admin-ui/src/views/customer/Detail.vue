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
import { reactive, ref, onMounted } from 'vue'
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

onMounted(() => Promise.all([loadCustomer(), loadConversations()]))
</script>
<style scoped>
.detail-header,
.header-actions { display:flex; align-items:center; gap:8px; }
.detail-header { justify-content:space-between; }
.conversation-section { margin-top:24px; }
.conversation-section h3 { margin:0 0 12px; font-size:16px; }
.conversation-pagination { margin-top:16px; }
@media (max-width: 560px) {
  .detail-header { align-items:flex-start; flex-direction:column; }
  .header-actions { flex-wrap:wrap; }
}
</style>
