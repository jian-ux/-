<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>知识库上传</span>
        <el-button @click="$router.push('/knowledge/faq')">返回常见问题列表</el-button>
      </div>
    </template>
    <el-upload drag :action="uploadUrl" :headers="uploadHeaders" :on-success="handleSuccess" :on-error="handleError" multiple
      accept=".txt,.md,.html,.htm,.docx,.pdf,.xlsx,.xls,.png,.jpg,.jpeg,.bmp,.tif,.tiff" style="margin-bottom:20px">
      <el-icon style="font-size:48px;color:#409eff"><Upload /></el-icon>
      <div style="margin-top:8px">拖拽文件到此处或点击上传</div>
      <template #tip><div style="font-size:12px;color:#999">支持常用文档和图片格式；图片会自动识别文字</div></template>
    </el-upload>

    <el-table :data="documents" row-key="id" border stripe v-loading="loading" @expand-change="handleExpandChange">
      <el-table-column type="expand">
        <template #default="{row}">
          <div style="padding:10px 20px" v-loading="row._loading">
            <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center">
              <span style="font-weight:bold">切片列表 ({{ row._chunks?.length || 0 }})</span>
              <el-tag size="small" type="info">仅已审核内容参与检索</el-tag>
              <el-tag size="small" :type="vectorTag(row)">{{ vectorText(row) }}</el-tag>
              <el-button size="small" type="success" @click.stop="approveAll(row)" v-if="row._chunks?.some(c=>c.status!=='APPROVED')">全部通过</el-button>
            </div>
            <div v-if="!row._chunks || row._chunks.length===0" style="color:#999">暂无切片</div>
            <div v-for="chunk in row._chunks" :key="chunk.id" style="margin-bottom:8px;padding:8px;background:#fafafa;border-radius:6px;display:flex;gap:10px;align-items:flex-start">
              <div style="flex:1;min-width:0">
                <div style="font-size:12px;color:#999;margin-bottom:4px;display:flex;gap:6px;align-items:center">
                  <span>#{{ chunk.chunkIndex }} · {{ chunk.content?.length || 0 }} 字</span>
                  <el-tag v-if="chunk.contentType === 'QA'" size="small" type="primary">
                    结构化问答 v{{ chunk.qaVersion || 1 }}
                  </el-tag>
                </div>
                <div v-if="chunk.contentType === 'QA'" class="qa-question">
                  {{ contentText(chunk.qaQuestion, '未识别问题') }}
                </div>
                <div style="font-size:13px;line-height:1.5;white-space:pre-wrap;max-height:120px;overflow:hidden" v-html="highlightChunk(chunk.content)"></div>
              </div>
              <div style="display:flex;gap:4px;align-items:center;flex-shrink:0">
                <el-tag size="small" :type="chunk.status==='APPROVED'?'success':chunk.status==='REJECTED'?'danger':'warning'">
                  {{ chunk.status==='APPROVED'?'已审核':chunk.status==='REJECTED'?'已拒绝':'待审核' }}
                </el-tag>
                <el-switch v-if="isQaGroupHead(row, chunk)"
                  :model-value="chunk.directAnswerEnabled === 1"
                  :loading="chunk._directUpdating"
                  :disabled="chunk.directAnswerEnabled !== 1 && !canEnableDirectQa(row, chunk)"
                  active-text="直答"
                  @click.stop
                  @change="enabled => toggleDirectAnswer(row, chunk, enabled)" />
                <el-button v-if="chunk.status!=='APPROVED'" size="small" type="success" @click.stop="approve(row,chunk)">通过</el-button>
                <el-button v-if="chunk.status!=='REJECTED'" size="small" type="danger" @click.stop="reject(row,chunk)">拒绝</el-button>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="文件名" min-width="220">
        <template #default="{row}">
          <div class="file-cell">
            <el-icon><Picture v-if="row.mediaType === 'IMAGE'" /><Document v-else /></el-icon>
            <span>{{ contentText(row.title, '已上传文件') }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="fileSize" label="大小" width="120">
        <template #default="{row}">{{ formatFileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="处理状态" width="130">
        <template #default="{row}">
          <el-tag :type="processingTag(row)" size="small">{{ processingText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="向量状态" width="130">
        <template #default="{row}">
          <el-tag :type="vectorTag(row)" size="small">{{ vectorText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核状态" width="120">
        <template #default="{row}">
          <el-tag :type="reviewTag(row)" size="small">{{ reviewText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="文字识别" width="130">
        <template #default="{row}">
          <span v-if="row.mediaType !== 'IMAGE'">-</span>
          <el-tag v-else :type="ocrTag(row.ocrStatus)" size="small">{{ ocrText(row.ocrStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="上传时间" width="180">
        <template #default="{row}">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="{row}">
          <el-button v-if="row.mediaType==='IMAGE' && row.ocrStatus==='FAILED'" size="small" @click.stop="retryOcr(row)">重试</el-button>
          <el-button v-if="canRetryEmbedding(row)" size="small" type="warning"
            :loading="row._embeddingRetrying" @click.stop="retryEmbedding(row)">
            <el-icon><Refresh /></el-icon>补齐向量
          </el-button>
          <el-button size="small" type="danger" @click.stop="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { Upload, Picture, Document, Refresh } from '@element-plus/icons-vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { contentText, formatDateTime } from '../../utils/displayText.js'

const documents = ref([])
const loading = ref(false)
const uploadUrl = '/api/admin/doc/upload'
const uploadHeaders = { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') }
const expandedRows = ref({})

function formatFileSize(size) {
  if (size == null) return '-'
  return (size / 1024).toFixed(1) + ' 千字节'
}

function highlightChunk(text) {
  if (!text) return ''
  const preview = contentText(text, '知识库内容').substring(0, 400)
  return escapeHtml(preview).replace(/[。！？\n]/g, '$&<wbr>')
}

function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

function reviewTag(row) {
  const chunks = row._chunks
  const total = chunks?.length ?? row.chunkCount ?? 0
  const approved = chunks
    ? chunks.filter(c => c.status === 'APPROVED').length
    : row.approvedCount ?? 0
  if (total > 0 && approved === total) return 'success'
  if (approved > 0) return 'warning'
  return 'info'
}

function reviewText(row) {
  if (row.mediaType === 'IMAGE' && row.ocrStatus === 'PROCESSING') return '识别中'
  if (row.mediaType === 'IMAGE' && row.ocrStatus === 'FAILED') return '识别失败'
  const chunks = row._chunks
  const total = chunks?.length ?? row.chunkCount ?? 0
  if (total === 0) return '无切片'
  const approved = chunks
    ? chunks.filter(c => c.status === 'APPROVED').length
    : row.approvedCount ?? 0
  return `${approved}/${total} 已审核`
}

function processingTag(row) {
  if (row.status === 1) return 'warning'
  if (row.status === 3) return 'danger'
  return row.status === 2 ? 'success' : 'info'
}

function processingText(row) {
  if (row.status === 1) return '解析中'
  if (row.status === 3) return '处理失败'
  if (row.status === 2) return '处理完成'
  return '未知'
}

function vectorCounts(row) {
  const chunks = row._chunks
  return {
    total: chunks?.length ?? row.chunkCount ?? 0,
    embedded: chunks
      ? chunks.filter(c => !!c.embedding).length
      : row.embeddingCount ?? 0
  }
}

function vectorTag(row) {
  const { total, embedded } = vectorCounts(row)
  if (total === 0) return 'info'
  if (embedded === total) return 'success'
  return embedded > 0 ? 'warning' : 'danger'
}

function vectorText(row) {
  const { total, embedded } = vectorCounts(row)
  return total === 0 ? '无向量' : `${embedded}/${total} 已生成`
}

function canRetryEmbedding(row) {
  const { total, embedded } = vectorCounts(row)
  return row.status !== 1 && total > 0 && embedded < total
}

function ocrTag(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

function ocrText(status) {
  if (status === 'COMPLETED') return '已识别'
  if (status === 'FAILED') return '识别失败'
  return '识别中'
}

async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/doc/list')
    documents.value = r.data?.records || []
  } catch(e) {
    documents.value = []
  } finally { loading.value = false }
}

async function handleExpandChange(row, expandedRows) {
  const expanded = Array.isArray(expandedRows)
    ? expandedRows.some(item => item.id === row.id)
    : Boolean(expandedRows)
  if (!expanded || row._chunks) return
  row._loading = true
  try {
    const r = await request.get('/admin/doc/' + row.id + '/chunks')
    row._chunks = r.data || []
  } catch(e) { row._chunks = [] }
  finally { row._loading = false }
}

async function approve(row, chunk) {
  try {
    await request.post('/admin/doc/chunks/' + chunk.id + '/approve')
    chunk.status = 'APPROVED'
    ElMessage.success('已通过')
  } catch(e) {}
}

async function reject(row, chunk) {
  try {
    await request.post('/admin/doc/chunks/' + chunk.id + '/reject')
    chunk.status = 'REJECTED'
    qaGroupChunks(row, chunk).forEach(item => { item.directAnswerEnabled = 0 })
    ElMessage.success('已拒绝')
  } catch(e) {}
}

function qaGroupChunks(row, chunk) {
  if (chunk.contentType !== 'QA') return []
  return (row._chunks || []).filter(item => item.contentType === 'QA'
    && (item.qaGroupKey || item.qaKey || item.qaQuestion)
      === (chunk.qaGroupKey || chunk.qaKey || chunk.qaQuestion))
}

function isQaGroupHead(row, chunk) {
  const group = qaGroupChunks(row, chunk)
  return group.length > 0 && group[0].id === chunk.id
}

function canEnableDirectQa(row, chunk) {
  const group = qaGroupChunks(row, chunk)
  return group.length > 0 && group.every(item => item.status === 'APPROVED')
}

async function toggleDirectAnswer(row, chunk, enabled) {
  chunk._directUpdating = true
  try {
    const r = await request.put('/admin/doc/chunks/' + chunk.id + '/direct-answer', {
      enabled,
      version: chunk.qaVersion || 1
    })
    qaGroupChunks(row, chunk).forEach(item => {
      item.directAnswerEnabled = enabled ? 1 : 0
      item.qaVersion = r.data?.version || item.qaVersion || 1
    })
    ElMessage.success(enabled ? '该标准问答已启用直答' : '该标准问答已停用直答')
  } catch (e) {
    ElMessage.error(e?.message || '直答设置失败')
  } finally {
    chunk._directUpdating = false
  }
}

async function approveAll(row) {
  try {
    await ElMessageBox.confirm('确认通过该文档所有切片？', '提示', { type: 'info' })
    const pending = (row._chunks || []).filter(c => c.status !== 'APPROVED')
    for (const c of pending) {
      await request.post('/admin/doc/chunks/' + c.id + '/approve')
      c.status = 'APPROVED'
    }
    ElMessage.success(`已通过 ${pending.length} 条切片`)
  } catch(e) {}
}

async function retryOcr(row) {
  try {
    await request.post('/admin/doc/' + row.id + '/ocr/retry')
    row.ocrStatus = 'PROCESSING'
    ElMessage.success('已重新提交文字识别')
    setTimeout(fetch, 2000)
  } catch(e) {}
}

async function retryEmbedding(row) {
  row._embeddingRetrying = true
  try {
    const r = await request.post('/admin/doc/' + row.id + '/embedding/retry', {}, { timeout: 180000 })
    const result = r.data || {}
    if ((result.remaining ?? 0) === 0) {
      ElMessage.success(`已补齐 ${result.generated || 0} 条向量`)
    } else {
      ElMessage.warning(`已生成 ${result.generated || 0} 条，仍缺少 ${result.remaining} 条`)
    }
    row._chunks = null
    await fetch()
  } catch(e) {
    ElMessage.error('向量补齐失败，请稍后重试')
  } finally {
    row._embeddingRetrying = false
  }
}

function handleSuccess() {
  ElMessage.success('上传成功，正在解析...')
  setTimeout(fetch, 2000)
  setTimeout(fetch, 6000)
  setTimeout(fetch, 15000)
}

function handleError() { ElMessage.error('上传失败，请检查文件格式和网络连接') }

async function del(id) {
  try {
    await ElMessageBox.confirm('确认删除？', '提示', {type:'warning'})
    await request.delete('/admin/doc/' + id)
    ElMessage.success('已删除'); fetch()
  } catch(e) {}
}

onMounted(fetch)
</script>

<style scoped>
.file-cell { display:flex; align-items:center; gap:8px; min-width:0; }
.file-cell span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.qa-question { margin-bottom:4px; font-size:13px; font-weight:600; color:#303133; }
</style>
