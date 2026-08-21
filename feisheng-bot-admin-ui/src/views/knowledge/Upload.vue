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

    <el-table ref="documentTable" :data="documents" row-key="id" border stripe
      v-loading="loading" @expand-change="handleExpandChange">
      <el-table-column type="expand">
        <template #default="{row}">
          <div class="chunk-workspace" v-loading="row._loading">
            <div class="chunk-heading">
              <div class="chunk-summary">
                <strong>切片列表</strong>
                <span class="chunk-count">{{ filteredChunks(row).length }}/{{ row._chunks?.length || 0 }}</span>
                <el-tag size="small" type="info">已发布且已审核内容参与检索</el-tag>
                <el-tag size="small" :type="vectorTag(row)">{{ vectorText(row) }}</el-tag>
              </div>
              <div class="chunk-heading-actions">
                <el-button size="small" type="success" @click.stop="approveAll(row)"
                  v-if="canReview(row) && row._chunks?.some(c=>c.status!=='APPROVED')">
                  <el-icon><CircleCheck /></el-icon>全部通过
                </el-button>
                <el-button size="small" @click.stop="collapseDocument(row)">
                  <el-icon><ArrowUp /></el-icon>收起
                </el-button>
              </div>
            </div>
            <el-alert v-if="isQualityBlocked(row)" type="error" :closable="false"
              show-icon :title="contentText(row.qualityMessage, '文档结构检查未通过，请修正文档后重新上传')"
              style="margin-bottom:10px" />
            <div v-if="row._chunks?.length" class="chunk-toolbar">
              <el-input v-model="row._chunkQuery" clearable placeholder="搜索问题或切片内容"
                class="chunk-search" @input="resetChunkPage(row)">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-select v-model="row._chunkStatus" class="chunk-filter" @change="resetChunkPage(row)">
                <el-option label="全部状态" value="ALL" />
                <el-option label="待审核" value="PENDING" />
                <el-option label="已审核" value="APPROVED" />
                <el-option label="已拒绝" value="REJECTED" />
              </el-select>
              <el-select v-model="row._chunkType" class="chunk-filter" @change="resetChunkPage(row)">
                <el-option label="全部类型" value="ALL" />
                <el-option label="结构化问答" value="QA" />
                <el-option label="普通文本" value="TEXT" />
              </el-select>
              <el-button v-if="hasChunkFilters(row)" @click="clearChunkFilters(row)">
                <el-icon><RefreshLeft /></el-icon>重置
              </el-button>
            </div>
            <el-empty v-if="!row._loading && !row._chunks?.length" description="暂无切片" :image-size="64" />
            <el-empty v-else-if="!row._loading && filteredChunks(row).length===0"
              description="没有符合条件的切片" :image-size="64" />
            <div v-else class="chunk-list">
              <div v-for="chunk in visibleChunks(row)" :key="chunk.id" class="chunk-item">
                <div class="chunk-content">
                  <div class="chunk-meta">
                    <span>#{{ chunk.chunkIndex }} · {{ chunk.content?.length || 0 }} 字</span>
                    <el-tag v-if="chunk.contentType === 'QA'" size="small" type="primary">
                      结构化问答 v{{ chunk.qaVersion || 1 }}
                    </el-tag>
                  </div>
                  <div v-if="chunk.contentType === 'QA'" class="qa-question">
                    {{ contentText(chunk.qaQuestion, '未识别问题') }}
                  </div>
                  <div class="chunk-preview" v-html="highlightChunk(chunk.content)"></div>
                </div>
                <div class="chunk-actions">
                  <el-tag size="small" :type="chunk.status==='APPROVED'?'success':chunk.status==='REJECTED'?'danger':'warning'">
                    {{ chunk.status==='APPROVED'?'已审核':chunk.status==='REJECTED'?'已拒绝':'待审核' }}
                  </el-tag>
                  <el-switch v-if="isQaGroupHead(row, chunk)"
                    :model-value="chunk.directAnswerEnabled === 1"
                    :loading="chunk._directUpdating"
                    :disabled="isQualityBlocked(row) || (chunk.directAnswerEnabled !== 1 && !canEnableDirectQa(row, chunk))"
                    active-text="直答"
                    @click.stop
                    @change="enabled => toggleDirectAnswer(row, chunk, enabled)" />
                  <el-button v-if="canReview(row) && chunk.status!=='APPROVED'" size="small"
                    type="success" @click.stop="approve(row,chunk)">通过</el-button>
                  <el-button v-if="chunk.status!=='REJECTED'" size="small" type="danger"
                    @click.stop="reject(row,chunk)">拒绝</el-button>
                </div>
              </div>
            </div>
            <div v-if="filteredChunks(row).length > 0" class="chunk-pagination">
              <el-pagination v-model:current-page="row._chunkPage" :page-size="10"
                :total="filteredChunks(row).length"
                layout="total, prev, pager, next" />
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
      <el-table-column label="导入质量" width="210">
        <template #default="{row}">
          <el-tooltip :content="contentText(row.qualityMessage, qualityText(row))" placement="top" :show-after="300">
            <el-tag :type="qualityTag(row)" size="small">{{ qualityText(row) }}</el-tag>
          </el-tooltip>
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
      <el-table-column label="发布状态" width="140">
        <template #default="{row}">
          <el-tag :type="publicationTag(row)" size="small">
            {{ publicationText(row) }} · v{{ row.documentVersion || 1 }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="130">
        <template #default="{row}">
          <el-input-number v-model="row.priority" :min="-100" :max="100" size="small"
            class="priority-input"
            controls-position="right" @change="value => updatePriority(row, value)" />
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
      <el-table-column label="操作" width="360">
        <template #default="{row}">
          <el-button v-if="row.mediaType==='IMAGE' && row.ocrStatus==='FAILED'" size="small" @click.stop="retryOcr(row)">重试</el-button>
          <el-button v-if="canRetryEmbedding(row)" size="small" type="warning"
            :loading="row._embeddingRetrying" @click.stop="retryEmbedding(row)">
            <el-icon><Refresh /></el-icon>补齐向量
          </el-button>
          <el-button v-if="canPublish(row)" size="small" type="success"
            :loading="row._publishing" @click.stop="publishDocument(row)">
            <el-icon><Promotion /></el-icon>发布
          </el-button>
          <el-button v-if="row.publishStatus === 'PUBLISHED'" size="small"
            :loading="row._archiving" @click.stop="archiveDocument(row)">
            <el-icon><FolderRemove /></el-icon>归档
          </el-button>
          <el-button size="small" type="danger" @click.stop="del(row.id)">删除</el-button>
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
</template>
<script setup>
import { ref, onMounted } from 'vue'
import {
  ArrowUp, CircleCheck, Document, FolderRemove, Picture, Promotion,
  Refresh, RefreshLeft, Search, Upload
} from '@element-plus/icons-vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { contentText, formatDateTime } from '../../utils/displayText.js'

const documents = ref([])
const loading = ref(false)
const documentTable = ref(null)
const total = ref(0)
const page = ref(1)
const pageSize = 10
const uploadUrl = '/api/admin/doc/upload'
const uploadHeaders = { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') }

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
  if (row.status === 3 || row.status === 4) return 'danger'
  return row.status === 2 ? 'success' : 'info'
}

function processingText(row) {
  if (row.status === 1) return '解析中'
  if (row.status === 3) return '处理失败'
  if (row.status === 4) return '结构异常'
  if (row.status === 2) return '处理完成'
  return '未知'
}

function isQualityBlocked(row) {
  return row.status === 4 || row.qualityStatus === 'BLOCKED'
}

function canReview(row) {
  return row.status === 2 && !isQualityBlocked(row)
}

function publicationTag(row) {
  if (row.publishStatus === 'PUBLISHED') return 'success'
  if (row.publishStatus === 'ARCHIVED') return 'info'
  return 'warning'
}

function publicationText(row) {
  if (row.publishStatus === 'PUBLISHED') return '已发布'
  if (row.publishStatus === 'ARCHIVED') return '已归档'
  return '草稿'
}

function canPublish(row) {
  if (row.publishStatus === 'PUBLISHED' || row.status !== 2 || isQualityBlocked(row)) return false
  const { total, embedded } = vectorCounts(row)
  const chunks = row._chunks
  const approved = chunks
    ? chunks.filter(chunk => chunk.status === 'APPROVED').length
    : row.approvedCount ?? 0
  return total > 0 && embedded === total && approved === total
}

function qualityTag(row) {
  if (isQualityBlocked(row) || row.qualityStatus === 'FAILED') return 'danger'
  if (row.qualityStatus === 'WARNING' || row.qualityStatus === 'PROCESSING') return 'warning'
  if (row.qualityStatus === 'PASSED') return 'success'
  return 'info'
}

function qualityText(row) {
  if (row.qualityStatus === 'PROCESSING') return '检查中'
  if (isQualityBlocked(row)) return '未通过'
  if (row.qualityStatus === 'FAILED') return '检查失败'
  if ((row.sourceRowCount || 0) > 0) {
    return `${row.sourceRowCount} 行 / ${row.detectedQaCount || 0} 问答`
  }
  if (row.qualityStatus === 'WARNING') return '通过，有提示'
  if (row.qualityStatus === 'PASSED') return '检查通过'
  return '未检查'
}

function vectorCounts(row) {
  const chunks = row._chunks
  return {
    total: chunks?.length ?? row.chunkCount ?? 0,
    embedded: chunks
      ? chunks.filter(c => c.hasEmbedding ?? !!c.embedding).length
      : row.embeddingCount ?? 0
  }
}

function initializeChunkWorkspace(row) {
  row._chunkQuery ??= ''
  row._chunkStatus ??= 'ALL'
  row._chunkType ??= 'ALL'
  row._chunkPage ??= 1
}

function filteredChunks(row) {
  const chunks = row._chunks || []
  const query = (row._chunkQuery || '').trim().toLocaleLowerCase()
  return chunks.filter(chunk => {
    if (row._chunkStatus && row._chunkStatus !== 'ALL' && chunk.status !== row._chunkStatus) return false
    if (row._chunkType && row._chunkType !== 'ALL' && chunk.contentType !== row._chunkType) return false
    if (!query) return true
    return [chunk.qaQuestion, chunk.sectionPath, chunk.content]
      .some(value => String(value || '').toLocaleLowerCase().includes(query))
  })
}

function visibleChunks(row) {
  const filtered = filteredChunks(row)
  const size = 10
  const lastPage = Math.max(1, Math.ceil(filtered.length / size))
  const page = Math.min(row._chunkPage || 1, lastPage)
  const start = (page - 1) * size
  return filtered.slice(start, start + size)
}

function resetChunkPage(row) {
  row._chunkPage = 1
}

function hasChunkFilters(row) {
  return Boolean((row._chunkQuery || '').trim())
    || !['', 'ALL'].includes(row._chunkStatus)
    || !['', 'ALL'].includes(row._chunkType)
}

function clearChunkFilters(row) {
  row._chunkQuery = ''
  row._chunkStatus = 'ALL'
  row._chunkType = 'ALL'
  resetChunkPage(row)
}

function collapseDocument(row) {
  documentTable.value?.toggleRowExpansion(row, false)
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
  return row.status !== 1 && !isQualityBlocked(row) && total > 0 && embedded < total
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
    const r = await request.get('/admin/doc/list', { params: { page: page.value, size: pageSize } })
    documents.value = r.data?.records || []
    total.value = r.data?.total || 0
  } catch {
    documents.value = []
    total.value = 0
  } finally { loading.value = false }
}

async function handleExpandChange(row, expandedRows) {
  const expanded = Array.isArray(expandedRows)
    ? expandedRows.some(item => item.id === row.id)
    : Boolean(expandedRows)
  if (!expanded || row._chunks) return
  initializeChunkWorkspace(row)
  row._loading = true
  try {
    const r = await request.get('/admin/doc/' + row.id + '/chunks')
    row._chunks = r.data || []
  } catch { row._chunks = [] }
  finally { row._loading = false }
}

async function approve(row, chunk) {
  try {
    await request.post('/admin/doc/chunks/' + chunk.id + '/approve')
    chunk.status = 'APPROVED'
    resetChunkPage(row)
    ElMessage.success('已通过')
  } catch {}
}

async function reject(row, chunk) {
  try {
    await request.post('/admin/doc/chunks/' + chunk.id + '/reject')
    chunk.status = 'REJECTED'
    qaGroupChunks(row, chunk).forEach(item => { item.directAnswerEnabled = 0 })
    resetChunkPage(row)
    ElMessage.success('已拒绝')
  } catch {}
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
    const r = await request.post('/admin/doc/' + row.id + '/approve-all', {}, { timeout: 180000 })
    const chunks = row._chunks || []
    chunks.forEach(chunk => { chunk.status = 'APPROVED' })
    row.approvedCount = row.chunkCount || row._chunks?.length || 0
    resetChunkPage(row)
    ElMessage.success(`已通过 ${r.data?.approved || 0} 条切片`)
  } catch {}
}

async function retryOcr(row) {
  try {
    await request.post('/admin/doc/' + row.id + '/ocr/retry')
    row.ocrStatus = 'PROCESSING'
    ElMessage.success('已重新提交文字识别')
    setTimeout(fetch, 2000)
  } catch {}
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

async function publishDocument(row) {
  row._publishing = true
  try {
    await ElMessageBox.confirm(
      `确认发布 v${row.documentVersion || 1}？同一知识集的旧版本将自动归档。`,
      '发布知识库', { type: 'warning' })
    const r = await request.post('/admin/doc/' + row.id + '/publish')
    row.publishStatus = 'PUBLISHED'
    row.documentVersion = r.data?.documentVersion || row.documentVersion || 1
    ElMessage.success('知识库已发布，客服检索索引已刷新')
    await fetch()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '发布失败')
  } finally {
    row._publishing = false
  }
}

async function archiveDocument(row) {
  row._archiving = true
  try {
    await ElMessageBox.confirm('归档后客服将不再检索该版本，确认继续？', '归档知识库', {
      type: 'warning'
    })
    await request.post('/admin/doc/' + row.id + '/archive')
    row.publishStatus = 'ARCHIVED'
    ElMessage.success('知识库已归档')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '归档失败')
  } finally {
    row._archiving = false
  }
}

async function updatePriority(row, value) {
  try {
    await request.put('/admin/doc/' + row.id + '/priority', { priority: value })
    ElMessage.success('优先级已更新')
  } catch (e) {
    ElMessage.error(e?.message || '优先级更新失败')
    await fetch()
  }
}

function handleSuccess() {
  ElMessage.success('上传成功，正在解析...')
  page.value = 1
  setTimeout(fetch, 2000)
  setTimeout(fetch, 6000)
  setTimeout(fetch, 15000)
}

function handleError() { ElMessage.error('上传失败，请检查文件格式和网络连接') }

async function del(id) {
  try {
    await ElMessageBox.confirm('确认删除？', '提示', {type:'warning'})
    await request.delete('/admin/doc/' + id)
    ElMessage.success('已删除')
    if (documents.value.length === 1 && page.value > 1) page.value--
    fetch()
  } catch {}
}

onMounted(fetch)
</script>

<style scoped>
.file-cell { display:flex; align-items:center; gap:8px; min-width:0; }
.file-cell span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.chunk-workspace { padding:12px 20px 16px; background:#f7f8fa; }
.chunk-heading,
.chunk-summary,
.chunk-heading-actions,
.chunk-toolbar,
.chunk-actions { display:flex; align-items:center; gap:8px; }
.chunk-heading { justify-content:space-between; margin-bottom:10px; }
.chunk-summary { min-width:0; flex-wrap:wrap; }
.chunk-count { color:#606266; font-size:13px; font-variant-numeric:tabular-nums; }
.chunk-heading-actions { flex-shrink:0; }
.chunk-toolbar {
  position:sticky;
  top:0;
  z-index:2;
  flex-wrap:wrap;
  padding:10px 0;
  background:#f7f8fa;
}
.chunk-search { width:320px; max-width:100%; }
.chunk-filter { width:140px; }
.priority-input { width:104px; }
.chunk-list {
  max-height:min(58vh, 620px);
  overflow:auto;
  padding-right:6px;
  scrollbar-gutter:stable;
}
.chunk-item {
  display:flex;
  align-items:flex-start;
  gap:12px;
  min-height:96px;
  margin-bottom:8px;
  padding:12px;
  border:1px solid #e4e7ed;
  border-radius:6px;
  background:#fff;
}
.chunk-content { flex:1; min-width:0; }
.chunk-meta { display:flex; align-items:center; gap:6px; margin-bottom:4px; color:#909399; font-size:12px; }
.chunk-preview { max-height:120px; overflow:hidden; white-space:pre-wrap; font-size:13px; line-height:1.5; }
.chunk-actions { flex-shrink:0; flex-wrap:wrap; justify-content:flex-end; max-width:280px; }
.chunk-pagination { display:flex; justify-content:flex-end; padding-top:10px; }
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
.qa-question { margin-bottom:4px; font-size:13px; font-weight:600; color:#303133; }

@media (max-width: 900px) {
  .chunk-workspace { padding:10px; }
  .chunk-heading { align-items:flex-start; }
  .chunk-item { flex-direction:column; }
  .chunk-actions { max-width:none; width:100%; justify-content:flex-start; }
  .chunk-search { width:100%; }
  .chunk-filter { flex:1; min-width:120px; }
  .chunk-pagination { justify-content:flex-start; overflow-x:auto; }
}
</style>
