<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>智能模型管理</span>
        <el-button type="primary" :icon="Plus" @click="openAdd">新增模型</el-button>
      </div>
    </template>

    <el-alert type="info" :closable="false" style="margin-bottom:15px" show-icon>
      对话、知识抽取、向量检索、检索重排和语音能力分别使用各自的默认模型。
    </el-alert>

    <el-table v-loading="loading" :data="models" border stripe>
      <el-table-column label="默认" width="70" align="center">
        <template #default="{row}">
          <span v-if="row.isDefault === 1" style="color:#e6a23c;font-size:20px;cursor:default" title="当前默认模型">⭐</span>
          <span v-else style="color:#ccc;font-size:20px;cursor:pointer" title="点击设为默认" @click="setDefault(row.id)">☆</span>
        </template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型标识" min-width="140">
        <template #default="{row}">{{ modelDisplayName(row) }}</template>
      </el-table-column>
      <el-table-column prop="provider" label="供应商" width="110">
        <template #default="{row}">{{ providerText(row.provider) }}</template>
      </el-table-column>
      <el-table-column prop="modelType" label="类型" width="100">
        <template #default="{row}"><el-tag size="small">{{ modelTypeText(row.modelType) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-tag :type="row.status===1?'success':'danger'" size="small">{{row.status===1?'启用':'禁用'}}</el-tag></template>
      </el-table-column>
      <el-table-column prop="apiUrl" label="接口状态" width="100">
        <template #default="{row}">{{ row.apiUrl ? '已配置' : '未配置' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210">
        <template #default="{row}">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="warning" v-if="row.isDefault !== 1" @click="setDefault(row.id)">设为默认</el-button>
          <el-button size="small" type="danger" @click="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="fetch"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="isEdit?'编辑模型':'新增模型'" width="min(560px, calc(100vw - 28px))">
    <el-form :model="form" label-width="108px">
      <el-form-item label="模型标识"><el-input v-model="form.modelName" placeholder="请输入服务商提供的模型标识" /></el-form-item>
      <el-form-item label="供应商">
        <el-select v-model="form.provider" style="width:100%">
          <el-option label="开放式智能" value="openai" />
          <el-option label="深度求索" value="deepseek" />
          <el-option label="通义千问" value="qwen" />
          <el-option label="智谱" value="zhipu" />
          <el-option label="微软云" value="azure" />
          <el-option label="其他" value="other" />
        </el-select>
      </el-form-item>
      <el-form-item label="服务地址"><el-input v-model="form.apiUrl" placeholder="请输入兼容接口的完整服务地址" /></el-form-item>
      <el-form-item label="接口密钥">
        <el-input
          v-model="form.apiKey"
          show-password
          autocomplete="new-password"
          :placeholder="isEdit ? '已配置，留空保持原密钥' : '请输入服务商接口密钥'"
        />
      </el-form-item>
      <el-form-item label="模型用途">
        <el-select v-model="form.modelType" style="width:100%">
          <el-option label="大语言模型" value="LLM" />
          <el-option label="知识抽取模型" value="Extraction" />
          <el-option label="向量嵌入" value="Embedding" />
          <el-option label="检索重排" value="Rerank" />
          <el-option label="语音转文字" value="Speech" />
          <el-option label="文字转语音" value="TTS" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.modelType === 'TTS'" label="合成参数">
        <el-input
          v-model="form.parameters"
          type="textarea"
          :rows="3"
          placeholder="请输入声音、音频格式、语速等合成参数"
        />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
      </el-form-item>
    </el-form>
    <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelTypeText, providerText } from '../../utils/displayText.js'

const models = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = 10
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = reactive({modelName:'',provider:'deepseek',apiUrl:'',apiKey:'',modelType:'LLM',parameters:'',status:1})

function modelDisplayName(row) {
  return row.modelName || `${providerText(row.provider)}${modelTypeText(row.modelType)}`
}

async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/ai/model/list', { params: { p: page.value, s: pageSize } })
    models.value = r.data?.records || []
    total.value = r.data?.total || 0
    if (!models.value.length && total.value > 0 && page.value > 1) {
      page.value = Math.max(1, Math.ceil(total.value / pageSize))
      return await fetch()
    }
  } finally {
    loading.value = false
  }
}

function openAdd() {
  Object.assign(form, {modelName:'',provider:'deepseek',apiUrl:'',apiKey:'',modelType:'LLM',parameters:'',status:1})
  isEdit.value = false; dialogVisible.value = true
}

function edit(row) {
  Object.assign(form, row, {apiKey: ''}); isEdit.value = true; dialogVisible.value = true
}

async function save() {
  if (isEdit.value) await request.put('/admin/ai/model/save', form)
  else await request.post('/admin/ai/model/save', form)
  ElMessage.success('已保存'); dialogVisible.value = false; fetch()
}

async function setDefault(id) {
  await request.put('/admin/ai/model/' + id + '/set-default')
  ElMessage.success('已设为默认模型'); fetch()
}

async function del(id) {
  try {
    await ElMessageBox.confirm('确认删除？', '提示', {type:'warning'})
    await request.delete('/admin/ai/model/' + id)
    ElMessage.success('已删除')
    fetch()
  } catch(e) {}
}

onMounted(fetch)
</script>
<style scoped>
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
</style>
