<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>渠道配置</span>
        <div class="header-actions">
          <el-select
            v-model="filterChannelType"
            placeholder="渠道类型筛选"
            clearable
            class="channel-filter"
            @change="filterRecords"
          >
            <el-option label="网页" value="web" />
            <el-option label="企业微信" value="wechat" />
            <el-option label="钉钉" value="dingtalk" />
            <el-option label="其他" value="other" />
          </el-select>
          <el-button type="primary" @click="openAdd">新增渠道</el-button>
        </div>
      </div>
    </template>

    <el-table :data="records" border stripe v-loading="loading">
      <el-table-column prop="channelType" label="渠道类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="channelTypeColor(row.channelType)">
            {{ channelTypeLabel(row.channelType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channelName" label="渠道名称" min-width="150" />
      <el-table-column prop="configSummary" label="接入配置" min-width="160">
        <template #default="{row}">{{ configSummaryText(row) }}</template>
      </el-table-column>
      <el-table-column label="连接状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="connectionStatusMeta(row.connectionStatus).type" size="small">
            {{ connectionStatusMeta(row.connectionStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.channelType === 'dingtalk' || row.channelType === 'wechat'"
            size="small"
            :loading="testingId === row.id"
            @click="testConnection(row)"
          >
            测试连接
          </el-button>
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="fetch"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑渠道' : '新增渠道'"
      width="min(640px, calc(100vw - 32px))"
      destroy-on-close
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="116px">
        <el-form-item label="渠道类型" prop="channelType">
          <el-select v-model="form.channelType" style="width:100%" :disabled="isEdit">
            <el-option label="网页" value="web" />
            <el-option label="企业微信" value="wechat" />
            <el-option label="钉钉" value="dingtalk" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道名称" prop="channelName">
          <el-input v-model="form.channelName" maxlength="100" placeholder="如：售后服务钉钉机器人" />
        </el-form-item>

        <template v-if="form.channelType === 'dingtalk'">
          <el-form-item label="接入方式">
            <el-radio-group v-model="form.connectionMode">
              <el-radio-button value="stream">长连接模式</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="应用标识" prop="clientId">
            <el-input v-model="form.clientId" autocomplete="off" placeholder="请输入钉钉应用标识" />
          </el-form-item>
          <el-form-item label="应用密钥" prop="clientSecret">
            <el-input
              v-model="form.clientSecret"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="secretPlaceholder(form.clientSecretConfigured, '请输入钉钉应用密钥')"
            />
          </el-form-item>
          <el-form-item label="机器人标识">
            <el-input v-model="form.robotCode" autocomplete="off" placeholder="选填，默认使用应用标识" />
          </el-form-item>
        </template>

        <template v-else-if="form.channelType === 'wechat'">
          <el-form-item label="企业标识" prop="corpId">
            <el-input v-model="form.corpId" autocomplete="off" placeholder="请输入企业标识" />
          </el-form-item>
          <el-form-item label="应用密钥" prop="corpSecret">
            <el-input
              v-model="form.corpSecret"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="secretPlaceholder(form.corpSecretConfigured, '请输入应用密钥')"
            />
          </el-form-item>
          <el-form-item label="应用标识" prop="agentId">
            <el-input v-model="form.agentId" autocomplete="off" placeholder="请输入应用标识" />
          </el-form-item>
          <el-form-item label="回调 Token" prop="callbackToken">
            <el-input
              v-model="form.callbackToken"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="secretPlaceholder(form.callbackTokenConfigured, '请输入回调 Token')"
            />
          </el-form-item>
          <el-form-item label="回调 AESKey" prop="callbackAesKey">
            <el-input
              v-model="form.callbackAesKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="secretPlaceholder(form.callbackAesKeyConfigured, '请输入 EncodingAESKey')"
            />
          </el-form-item>
          <el-form-item label="接收消息 URL">
            <el-input model-value="/gateway/channel/wechat/message" readonly />
          </el-form-item>
        </template>

        <template v-else-if="form.channelType === 'other'">
          <el-form-item label="服务地址" prop="endpoint">
            <el-input v-model="form.endpoint" autocomplete="off" placeholder="请输入服务地址" />
          </el-form-item>
          <el-form-item label="访问令牌">
            <el-input
              v-model="form.accessToken"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="secretPlaceholder(form.accessTokenConfigured, '选填')"
            />
          </el-form-item>
        </template>

        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="status-text">{{ form.status === 1 ? '启用' : '禁用' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { channelTypeText, formatDateTime } from '../../utils/displayText.js'

const records = ref([])
const loading = ref(false)
const saving = ref(false)
const testingId = ref(null)
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const filterChannelType = ref('')
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const emptyForm = () => ({
  id: null,
  channelType: '',
  channelName: '',
  status: 1,
  connectionMode: 'stream',
  clientId: '',
  clientSecret: '',
  robotCode: '',
  clientSecretConfigured: false,
  corpId: '',
  corpSecret: '',
  agentId: '',
  corpSecretConfigured: false,
  callbackToken: '',
  callbackAesKey: '',
  callbackTokenConfigured: false,
  callbackAesKeyConfigured: false,
  endpoint: '',
  accessToken: '',
  accessTokenConfigured: false
})

const form = reactive(emptyForm())

const requiredWhen = (channelType, configuredField, message) => (_rule, value, callback) => {
  if (form.channelType !== channelType || form.status !== 1) return callback()
  if (String(value || '').trim() || (configuredField && form[configuredField])) return callback()
  callback(new Error(message))
}

const rules = {
  channelType: [{ required: true, message: '请选择渠道类型', trigger: 'change' }],
  channelName: [{ required: true, whitespace: true, message: '请输入渠道名称', trigger: 'blur' }],
  clientId: [{ validator: requiredWhen('dingtalk', null, '请输入钉钉应用标识'), trigger: 'blur' }],
  clientSecret: [{ validator: requiredWhen('dingtalk', 'clientSecretConfigured', '请输入钉钉应用密钥'), trigger: 'blur' }],
  corpId: [{ validator: requiredWhen('wechat', null, '请输入企业标识'), trigger: 'blur' }],
  corpSecret: [{ validator: requiredWhen('wechat', 'corpSecretConfigured', '请输入应用密钥'), trigger: 'blur' }],
  agentId: [{ validator: requiredWhen('wechat', null, '请输入应用标识'), trigger: 'blur' }],
  callbackToken: [{ validator: requiredWhen('wechat', 'callbackTokenConfigured', '请输入回调 Token'), trigger: 'blur' }],
  callbackAesKey: [{ validator: requiredWhen('wechat', 'callbackAesKeyConfigured', '请输入 EncodingAESKey'), trigger: 'blur' }],
  endpoint: [{ validator: requiredWhen('other', null, '请输入服务地址'), trigger: 'blur' }]
}

async function fetch() {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize }
    if (filterChannelType.value) params.channelType = filterChannelType.value
    const response = await request.get('/admin/channel/config/list', { params })
    records.value = response.data?.records || []
    total.value = response.data?.total || 0
  } finally {
    loading.value = false
  }
}

function filterRecords() {
  currentPage.value = 1
  fetch()
}

async function openAdd() {
  resetForm()
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

async function edit(row) {
  Object.assign(form, emptyForm(), {
    id: row.id,
    channelType: row.channelType,
    channelName: row.channelName || '',
    status: row.status,
    connectionMode: row.connectionMode || 'stream',
    clientId: row.clientId || '',
    robotCode: row.robotCode || '',
    clientSecretConfigured: Boolean(row.clientSecretConfigured),
    corpId: row.corpId || '',
    agentId: row.agentId || '',
    corpSecretConfigured: Boolean(row.corpSecretConfigured),
    callbackTokenConfigured: Boolean(row.callbackTokenConfigured),
    callbackAesKeyConfigured: Boolean(row.callbackAesKeyConfigured),
    endpoint: row.endpoint || '',
    accessTokenConfigured: Boolean(row.accessTokenConfigured)
  })
  isEdit.value = true
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

function resetForm() {
  Object.assign(form, emptyForm())
  isEdit.value = false
  formRef.value?.clearValidate()
}

async function save() {
  if (!formRef.value || saving.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      id: isEdit.value ? form.id : undefined,
      channelType: form.channelType,
      channelName: form.channelName.trim(),
      status: form.status,
      connectionMode: form.connectionMode,
      clientId: form.clientId.trim(),
      clientSecret: form.clientSecret.trim(),
      robotCode: form.robotCode.trim(),
      corpId: form.corpId.trim(),
      corpSecret: form.corpSecret.trim(),
      agentId: form.agentId.trim(),
      callbackToken: form.callbackToken.trim(),
      callbackAesKey: form.callbackAesKey.trim(),
      endpoint: form.endpoint.trim(),
      accessToken: form.accessToken.trim()
    }
    if (isEdit.value) await request.put('/admin/channel/config/save', payload)
    else await request.post('/admin/channel/config/save', payload)
    ElMessage.success('渠道已保存')
    dialogVisible.value = false
    await fetch()
  } finally {
    saving.value = false
  }
}

async function testConnection(row) {
  if (testingId.value) return
  testingId.value = row.id
  try {
    const response = await request.post(`/admin/channel/config/${row.id}/test`)
    if (response.data?.success === false) {
      ElMessage.error(response.data.message || '连接测试失败')
      return
    }
    ElMessage.success(response.data?.message || '连接测试成功')
    await fetch()
  } finally {
    testingId.value = null
  }
}

async function del(id) {
  try {
    await ElMessageBox.confirm('确认删除该渠道配置？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await request.delete('/admin/channel/config/' + id)
  ElMessage.success('已删除')
  if (records.value.length === 1 && currentPage.value > 1) currentPage.value--
  await fetch()
}

function secretPlaceholder(configured, emptyText) {
  return configured ? '已配置，留空则不修改' : emptyText
}

function channelTypeLabel(type) {
  return channelTypeText(type)
}

function configSummaryText(row) {
  return {
    web: '网页接入',
    wechat: '企业微信应用',
    dingtalk: '钉钉长连接',
    other: '自定义接入'
  }[row.channelType] || '未配置'
}

function channelTypeColor(type) {
  const colors = { web: '', wechat: 'success', dingtalk: 'primary', other: 'warning' }
  return colors[type] || 'info'
}

function connectionStatusMeta(status) {
  const statuses = {
    CONNECTED: { label: '已连接', type: 'success' },
    ENABLED: { label: '已启用', type: 'success' },
    NOT_CONNECTED: { label: '未连接', type: 'danger' },
    DISABLED: { label: '已禁用', type: 'info' }
  }
  return statuses[status] || { label: '未知', type: 'warning' }
}

function formatTime(value) {
  return formatDateTime(value)
}

onMounted(fetch)
</script>

<style scoped>
.page-header,
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-header {
  justify-content: space-between;
  flex-wrap: wrap;
}

.channel-filter {
  width: 150px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 15px;
  overflow-x: auto;
}

.status-text {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 640px) {
  .header-actions {
    width: 100%;
  }

  .channel-filter {
    flex: 1;
    width: auto;
  }

  :deep(.el-dialog__body) {
    padding-left: 14px;
    padding-right: 14px;
  }

  :deep(.el-form-item__label) {
    width: 104px !important;
  }

}
</style>
