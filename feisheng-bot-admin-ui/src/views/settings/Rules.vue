<template>
  <div>
    <el-card style="margin-bottom: 15px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>安全规则管理</span>
          <el-button type="primary" @click="openAdd">新增规则</el-button>
        </div>
      </template>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:15px">
        安全规则用于检测和过滤用户输入与智能回复中的风险内容。支持四种类型：强制转人工、敏感词拦截、禁答话题和智能回复免责声明。规则按优先级从小到大顺序匹配，命中即停止。
      </el-alert>

      <!-- 分类筛选 -->
      <el-row :gutter="15" style="margin-bottom:15px">
        <el-col :span="6" v-for="cat in categories" :key="cat.type">
          <el-card :class="['cat-card', cat.active ? 'cat-active' : '']" shadow="hover" @click="filterType = cat.active ? '' : cat.type">
            <div style="text-align:center">
              <div :style="{fontSize:'24px'}">{{ cat.icon }}</div>
              <div style="font-weight:bold;margin:5px 0">{{ cat.label }}</div>
              <el-tag :type="cat.color" size="small">{{ cat.count }}</el-tag>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- 规则列表 -->
    <el-card v-for="group in groupedRules" :key="group.type" style="margin-bottom:15px">
      <template #header>
        <div :style="{display:'flex',alignItems:'center',gap:'8px'}">
          <span :style="{fontSize:'18px'}">{{ group.icon }}</span>
          <span style="font-weight:bold">{{ group.label }}</span>
          <el-tag :type="group.color" size="small">{{ group.rules.length }}</el-tag>
        </div>
      </template>
      <el-table :data="group.rules" border stripe>
        <el-table-column prop="pattern" label="匹配模式" min-width="200">
          <template #default="{row}">
            <code>{{ localizedSystemText(row.pattern, '已配置匹配模式') }}</code>
            <el-tag v-if="row.isRegex" type="warning" size="small" style="margin-left:5px">正则</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="120">
          <template #default="{row}">
            <el-tag :type="actionTag(row.action)">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="replyText" label="固定回复" min-width="200" show-overflow-tooltip>
          <template #default="{row}"><span v-if="row.replyText">{{ localizedSystemText(row.replyText, '已配置固定回复') }}</span><span v-else style="color:#ccc">—</span></template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="150">
          <template #default="{row}">{{ localizedSystemText(row.description, '安全规则') }}</template>
        </el-table-column>
        <el-table-column prop="isEnabled" label="启用" width="80">
          <template #default="{row}">
            <el-switch :model-value="row.isEnabled===1" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{row}">
            <el-button size="small" @click="edit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <div v-if="filteredRules.length" class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="filteredRules.length"
        layout="total, prev, pager, next"
      />
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit?'编辑规则':'新增规则'" width="550px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="规则类型">
          <el-select v-model="form.ruleType" style="width:100%">
            <el-option label="强制转人工" value="FORCE_HANDOFF" />
            <el-option label="敏感词" value="SENSITIVE_WORD" />
            <el-option label="禁答话题" value="FORBIDDEN_TOPIC" />
            <el-option label="智能回复免责声明" value="AI_DISCLAIMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="匹配模式">
          <el-input v-model="form.pattern" type="textarea" :rows="2" placeholder="输入匹配关键词或正则表达式" />
        </el-form-item>
        <el-form-item label="正则匹配">
          <el-switch v-model="form.isRegex" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="执行动作">
          <el-select v-model="form.action" style="width:100%">
            <el-option label="拦截" value="BLOCK" />
            <el-option label="固定回复" value="REPLY_FIXED" />
            <el-option label="转人工" value="HANDOFF" />
            <el-option label="仅记录" value="LOG_ONLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="回复内容" v-if="form.action==='REPLY_FIXED'">
          <el-input v-model="form.replyText" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="规则说明">
          <el-input v-model="form.description" placeholder="规则用途说明" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.isEnabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive, watch } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { localizedErrorText, localizedSystemText } from '../../utils/displayText.js'

const rules = ref([])
const filterType = ref('')
const page = ref(1)
const pageSize = 10
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = reactive({
  ruleType: 'FORCE_HANDOFF', pattern: '', isRegex: 0, action: 'HANDOFF',
  replyText: '', description: '', priority: 10, isEnabled: 1
})

const categories = computed(() => [
  { type: 'FORCE_HANDOFF', label: '强制转人工', icon: '🚨', color: 'danger',
    count: rules.value.filter(r => r.ruleType==='FORCE_HANDOFF').length,
    active: filterType.value === 'FORCE_HANDOFF' },
  { type: 'SENSITIVE_WORD', label: '敏感词', icon: '🔇', color: 'warning',
    count: rules.value.filter(r => r.ruleType==='SENSITIVE_WORD').length,
    active: filterType.value === 'SENSITIVE_WORD' },
  { type: 'FORBIDDEN_TOPIC', label: '禁答话题', icon: '🚫', color: 'primary',
    count: rules.value.filter(r => r.ruleType==='FORBIDDEN_TOPIC').length,
    active: filterType.value === 'FORBIDDEN_TOPIC' },
  { type: 'AI_DISCLAIMER', label: '智能回复免责声明', icon: '🤖', color: 'success',
    count: rules.value.filter(r => r.ruleType==='AI_DISCLAIMER').length,
    active: filterType.value === 'AI_DISCLAIMER' },
])

const filteredRules = computed(() => (
  filterType.value
    ? rules.value.filter(r => r.ruleType === filterType.value)
    : rules.value
))

const groupedRules = computed(() => {
  const start = (page.value - 1) * pageSize
  const visible = filteredRules.value.slice(start, start + pageSize)
  const groups = {}
  for (const r of visible) {
    if (!groups[r.ruleType]) groups[r.ruleType] = []
    groups[r.ruleType].push(r)
  }
  const catMap = {
    FORCE_HANDOFF: { icon: '🚨', label: '强制转人工', color: 'danger' },
    SENSITIVE_WORD: { icon: '🔇', label: '敏感词', color: 'warning' },
    FORBIDDEN_TOPIC: { icon: '🚫', label: '禁答话题', color: 'primary' },
    AI_DISCLAIMER: { icon: '🤖', label: '智能回复免责声明', color: 'success' },
  }
  return Object.entries(groups).map(([type, rules]) => ({
    type, rules, ...catMap[type] || {}
  }))
})
watch(filterType, () => { page.value = 1 })

function actionTag(action) {
  return { BLOCK:'danger', REPLY_FIXED:'warning', HANDOFF:'primary', LOG_ONLY:'info' }[action] || 'info'
}
function actionLabel(action) {
  return { BLOCK:'拦截', REPLY_FIXED:'固定回复', HANDOFF:'转人工', LOG_ONLY:'仅记录' }[action] || '未知动作'
}

async function fetch() {
  try {
    const r = await request.get('/admin/rules/list', {params:{size:500}})
    rules.value = r.data?.records || []
    page.value = Math.min(page.value, Math.max(1, Math.ceil(filteredRules.value.length / pageSize)))
  } catch(e) { rules.value = [] }
}

function openAdd() {
  Object.assign(form, { ruleType:'FORCE_HANDOFF', pattern:'', isRegex:0, action:'HANDOFF', replyText:'', description:'', priority:10, isEnabled:1, id:undefined, createTime:undefined, updateTime:undefined, deleted:undefined })
  isEdit.value = false
  dialogVisible.value = true
}

function edit(row) {
  Object.assign(form, row)
  isEdit.value = true
  dialogVisible.value = true
}

async function save() {
  try {
    const res = isEdit.value ? await request.put('/admin/rules/save', form) : await request.post('/admin/rules/save', form)
    if (res.code !== 200) { ElMessage.error(localizedErrorText(res.msg, '保存失败')); return }
    ElMessage.success('已保存')
    dialogVisible.value = false
    fetch()
  } catch(e) {
    console.error('save failed', e)
    ElMessage.error(localizedErrorText(e.response?.data?.msg, '保存失败，请检查服务和数据库'))
  }
}

async function toggle(row) {
  try {
    await request.put('/admin/rules/' + row.id + '/toggle')
    ElMessage.success(row.isEnabled === 1 ? '已禁用' : '已启用')
    fetch()
  } catch(e) { console.error('toggle failed', e) }
}

async function del(id) {
  try {
    await ElMessageBox.confirm('确认删除？', '提示', { type: 'warning' })
    await request.delete('/admin/rules/' + id)
    ElMessage.success('已删除')
    fetch()
  } catch(e) { console.error('delete failed', e) }
}

onMounted(fetch)
</script>

<style scoped>
.cat-card { cursor: pointer; border: 2px solid transparent; transition: all 0.2s; }
.cat-active { border-color: #409eff; background: #ecf5ff; }
.pagination-wrap { display:flex; justify-content:flex-end; margin:0 0 15px; overflow-x:auto; }
</style>
