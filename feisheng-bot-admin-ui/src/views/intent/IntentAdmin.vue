<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>意图管理</span>
        <el-button type="primary" @click="openAdd">新增意图</el-button>
      </div>
    </template>
    <el-input
      v-model="intentName"
      placeholder="搜索意图名称"
      clearable
      style="width:260px;margin-bottom:15px"
      @clear="search"
      @keyup.enter="search"
    />
    <el-table :data="intents" border stripe v-loading="loading">
      <el-table-column prop="intentName" label="意图名称" min-width="150">
        <template #default="{row}">{{ localizedSystemText(row.intentName, '自定义意图') }}</template>
      </el-table-column>
      <el-table-column prop="intentKeywords" label="触发关键词" min-width="250" show-overflow-tooltip>
        <template #default="{row}">{{ localizedSystemText(row.intentKeywords, '已配置关键词') }}</template>
      </el-table-column>
      <el-table-column prop="replyTemplate" label="回复模板" min-width="300" show-overflow-tooltip>
        <template #default="{row}">{{ localizedSystemText(row.replyTemplate, '已配置回复模板') }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}">
          <el-switch :model-value="row.status===1" @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{row}">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:20px" layout="total,prev,pager,next" :total="total" :page-size="pageSize" :current-page="page" @current-change="onPageChange" />
    <el-dialog v-model="dialogVisible" :title="isEdit?'编辑意图':'新增意图'" width="min(550px, 92vw)">
      <el-form :model="form" label-width="100px">
        <el-form-item label="意图名称" required><el-input v-model="form.intentName" maxlength="100" placeholder="如：退款咨询" /></el-form-item>
        <el-form-item label="触发关键词" required><el-input v-model="form.intentKeywords" placeholder="逗号分隔，如：退款,退钱,退货" /></el-form-item>
        <el-form-item label="回复模板" required><el-input v-model="form.replyTemplate" type="textarea" :rows="4" placeholder="可使用 {{intent}} 和 {{keyword}}" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, onMounted, reactive } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { localizedSystemText } from '../../utils/displayText.js'
const intents = ref([]); const total = ref(0); const page = ref(1); const loading = ref(false)
const pageSize = 10
const intentName = ref(''); const saving = ref(false)
const dialogVisible = ref(false); const isEdit = ref(false)
const form = reactive({ id:null, intentName:'', intentKeywords:'', replyTemplate:'' })
async function fetch() {
  loading.value = true
  try { const r = await request.get('/admin/intent/list', {params:{page:page.value,size:pageSize,intentName:intentName.value.trim()}}); intents.value = r.data.records; total.value = r.data.total }
  catch(e) { intents.value = [] }
  finally { loading.value = false }
}
function onPageChange(val) { page.value = val; fetch() }
function search() { page.value=1; fetch() }
function openAdd() { Object.assign(form, {id:null,intentName:'',intentKeywords:'',replyTemplate:''}); isEdit.value=false; dialogVisible.value=true }
function edit(row) { Object.assign(form, {id:row.id,intentName:row.intentName,intentKeywords:row.intentKeywords,replyTemplate:row.replyTemplate}); isEdit.value=true; dialogVisible.value=true }
async function save() {
  if (!form.intentName.trim() || !form.intentKeywords.trim() || !form.replyTemplate.trim()) {
    ElMessage.warning('请填写完整的意图名称、触发关键词和回复模板')
    return
  }
  saving.value = true
  try {
    await request.post('/admin/intent/save', {
      id: form.id,
      intentName: form.intentName.trim(),
      intentKeywords: form.intentKeywords.trim(),
      replyTemplate: form.replyTemplate.trim()
    })
    ElMessage.success('已保存'); dialogVisible.value=false; fetch()
  } finally { saving.value = false }
}
async function toggle(row) { await request.put('/admin/intent/'+row.id+'/toggle'); ElMessage.success(row.status===1?'已禁用':'已启用'); fetch() }
async function del(id) { await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await request.delete('/admin/intent/'+id); ElMessage.success('已删除'); fetch() }
onMounted(fetch)
</script>
