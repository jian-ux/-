<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>回复策略管理</span>
        <el-button type="primary" @click="openAdd">新增策略</el-button>
      </div>
    </template>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:15px">
      回复策略按优先级从小到大的顺序评估。支持四种动作：拦截、仅常见问题、智能兜底、转人工。条件使用结构化格式。
    </el-alert>
    <el-table :data="strategies" border stripe v-loading="loading">
      <el-table-column label="优先级" width="80">
        <template #default="{row,$index}">{{ (page - 1) * pageSize + $index + 1 }}</template>
      </el-table-column>
      <el-table-column prop="strategyName" label="策略名称" min-width="150">
        <template #default="{row}">{{ localizedSystemText(row.strategyName, '回复策略') }}</template>
      </el-table-column>
      <el-table-column prop="action" label="动作" width="120">
        <template #default="{row}">
          <el-tag :type="{BLOCK:'danger',FAQ_ONLY:'primary',AI_FALLBACK:'warning',HANDOFF:'info'}[row.action]||'info'">
            {{ {BLOCK:'拦截',FAQ_ONLY:'仅常见问题',AI_FALLBACK:'智能兜底',HANDOFF:'转人工'}[row.action]||'未知动作' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ruleCondition" label="规则条件" min-width="120">
        <template #default="{row}">{{ row.ruleCondition ? '已配置' : '未配置' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-switch :model-value="row.status===1" @change="toggle(row)" /></template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{row}">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:20px" layout="total,prev,pager,next" :total="total" :page-size="pageSize" :current-page="page" @current-change="onPageChange" />
    <el-dialog v-model="dialogVisible" :title="isEdit?'编辑策略':'新增策略'" width="550px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="策略名称"><el-input v-model="form.strategyName" placeholder="如：退款转人工" /></el-form-item>
        <el-form-item label="动作">
          <el-select v-model="form.action" style="width:100%">
            <el-option label="拦截回复" value="BLOCK" />
            <el-option label="仅使用常见问题" value="FAQ_ONLY" />
            <el-option label="智能兜底" value="AI_FALLBACK" />
            <el-option label="转人工" value="HANDOFF" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则条件">
          <el-input v-model="form.ruleCondition" type="textarea" :rows="4" placeholder="请输入结构化规则条件" />
        </el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="0" :max="999" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, onMounted, reactive } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { localizedSystemText } from '../../utils/displayText.js'
const strategies = ref([]); const total = ref(0); const page = ref(1); const loading = ref(false)
const pageSize = 10
const dialogVisible = ref(false); const isEdit = ref(false)
const form = reactive({ strategyName:'', action:'BLOCK', ruleCondition:'', priority:10 })
async function fetch() {
  loading.value = true
  try { const r = await request.get('/admin/reply-strategy/list', {params:{page:page.value,size:pageSize}}); strategies.value = r.data.records; total.value = r.data.total }
  catch { strategies.value = [] }
  finally { loading.value = false }
}
function onPageChange(val) { page.value = val; fetch() }
function openAdd() { Object.assign(form, {strategyName:'',action:'BLOCK',ruleCondition:'',priority:10}); isEdit.value=false; dialogVisible.value=true }
function edit(row) { Object.assign(form, row); isEdit.value=true; dialogVisible.value=true }
async function save() { await request.post('/admin/reply-strategy/save', form); ElMessage.success('已保存'); dialogVisible.value=false; fetch() }
async function toggle(row) { await request.put('/admin/reply-strategy/'+row.id+'/toggle'); ElMessage.success(row.status===1?'已禁用':'已启用'); fetch() }
async function del(id) { await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await request.delete('/admin/reply-strategy/'+id); ElMessage.success('已删除'); fetch() }
onMounted(fetch)
</script>
