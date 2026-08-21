<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
    <span>常见问题管理</span>
    <el-button type="danger" :disabled="!selectedIds.length" @click="batchDelete" v-if="selectedIds.length">批量删除 ({{ selectedIds.length }})</el-button>
        <el-button type="primary" @click="openCreate">新增常见问题</el-button>
      </div>
    </template>
    <el-input v-model="keyword" placeholder="搜索常见问题" style="width:300px;margin-bottom:15px" clearable @clear="search" @keyup.enter="search" />
    <el-table :data="items" border stripe @selection-change="onSelectionChange">
      <el-table-column type="selection" width="50" />
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column prop="question" label="问题" min-width="200" />
      <el-table-column prop="answer" label="答案" min-width="300" show-overflow-tooltip>
        <template #default="{row}">{{ contentText(row.answer, '已配置答案') }}</template>
      </el-table-column>
      <el-table-column prop="keywords" label="关键词">
        <template #default="{row}">{{ contentText(row.keywords, '未配置关键词') }}</template>
      </el-table-column>
      <el-table-column label="原文直答" width="100">
        <template #default="{row}">
          <el-tag :type="row.directAnswerEnabled === 1 ? 'success' : 'info'" size="small">
            {{ row.directAnswerEnabled === 1 ? '允许' : 'AI 综合' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="向量状态" width="100">
        <template #default="{row}">
          <el-tag :type="row.embeddingReady ? 'success' : 'warning'" size="small">
            {{ row.embeddingReady ? '已生成' : '待生成' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="hitCount" label="命中次数" width="80" />
      <el-table-column label="操作" width="150">
        <template #default="{row}"><el-button size="small" @click="edit(row)">编辑</el-button><el-button size="small" type="danger" @click="del(row.id)">删除</el-button></template>
      </el-table-column>
    </el-table>
    <div style="display:flex;justify-content:flex-end;margin-top:15px">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total,prev,pager,next"
        @current-change="fetch"
      />
    </div>
  </el-card>
  <el-dialog v-model="dialogVisible" :title="isEdit?'编辑常见问题':'新增常见问题'" width="600px" @closed="resetForm">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="问题" prop="question">
        <el-input v-model="form.question" type="textarea" :rows="2" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="答案" prop="answer">
        <el-input v-model="form.answer" type="textarea" :rows="6" maxlength="20000" show-word-limit />
      </el-form-item>
      <el-form-item label="关键词" prop="keywords">
        <el-input v-model="form.keywords" placeholder="短关键词用逗号分隔；完整问法须以问号结尾" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="原文直答">
        <el-switch v-model="form.directAnswerEnabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="dialogVisible=false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { ref, onMounted, reactive, computed, nextTick } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { contentText } from '../../utils/displayText.js'
const items = ref([]); const keyword = ref(''); const dialogVisible = ref(false); const isEdit = ref(false)
const page = ref(1); const pageSize = 10; const total = ref(0)
const formRef = ref(); const saving = ref(false)
const selectedRows = ref([])
const selectedIds = computed(() => selectedRows.value.map(r => r.id))
const emptyForm = () => ({id:null,question:'',answer:'',keywords:'',directAnswerEnabled:false})
const form = reactive(emptyForm())
const rules = {
  question: [{ required: true, whitespace: true, message: '请输入问题', trigger: 'blur' }],
  answer: [{ required: true, whitespace: true, message: '请输入答案', trigger: 'blur' }]
}
const fetch = async () => {
  try {
    const r=await request.get('/admin/knowledge/item/search',{params:{page:page.value,size:pageSize,keyword:keyword.value}})
    items.value=r.data?.records||[]
    total.value=r.data?.total||0
  } catch { items.value=[]; total.value=0 }
}
const search = () => { page.value = 1; fetch() }
const resetForm = () => {
  Object.assign(form, emptyForm())
  isEdit.value = false
  formRef.value?.clearValidate()
}
const openCreate = async () => {
  resetForm()
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}
const edit = async (row) => {
  Object.assign(form, {
    id: row.id,
    question: row.question || '',
    answer: row.answer || '',
    keywords: row.keywords || '',
    directAnswerEnabled: row.directAnswerEnabled === 1
  })
  isEdit.value = true
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}
const del = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除这条常见问题吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch { return }
  await request.delete('/admin/knowledge/item/'+id)
  ElMessage.success('已删除')
  fetch()
}
const onSelectionChange = (rows) => { selectedRows.value = rows }
const batchDelete = async () => {
  const ids = selectedIds.value
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条常见问题吗？`, '批量删除确认', {
      type: 'warning',
      confirmButtonText: '批量删除',
      cancelButtonText: '取消'
    })
  } catch { return }
  await request.post('/admin/knowledge/item/batch-delete', ids)
  ElMessage.success('批量删除 ' + ids.length + ' 条成功')
  selectedRows.value = []
  fetch()
}
const save = async () => {
  if (!formRef.value || saving.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      question: form.question.trim(),
      answer: form.answer.trim(),
      keywords: form.keywords.trim(),
      directAnswerEnabled: form.directAnswerEnabled ? 1 : 0
    }
    if (isEdit.value) {
      payload.id = form.id
      await request.put('/admin/knowledge/item/update', payload)
    } else {
      await request.post('/admin/knowledge/item/add', payload)
    }
    ElMessage.success('已保存，向量正在生成')
    dialogVisible.value = false
    await fetch()
  } finally {
    saving.value = false
  }
}
onMounted(fetch)
</script>
