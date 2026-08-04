<template>
  <el-card class="semantic-units-page">
    <template #header>
      <div class="page-header">
        <span>结构化知识审核</span>
        <el-button type="primary" :icon="MagicStick" @click="openExtraction">
          发起抽取
        </el-button>
      </div>
    </template>

    <div class="filters">
      <el-input-number
        v-model="documentIdFilter"
        :min="1"
        :controls="false"
        placeholder="文档 ID"
        aria-label="按文档 ID 筛选"
        @keyup.enter="loadUnits"
      />
      <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="loadUnits">
        <el-option label="待审核" value="DRAFT" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已拒绝" value="REJECTED" />
      </el-select>
      <el-button type="primary" :icon="Search" :loading="loading" @click="loadUnits">
        查询
      </el-button>
      <el-button :icon="Refresh" :disabled="loading" @click="resetFilters">重置</el-button>
    </div>

    <el-alert
      v-if="listError"
      class="page-alert"
      :title="listError"
      type="error"
      show-icon
      closable
      @close="listError = ''"
    />
    <el-alert
      v-if="actionError"
      class="page-alert"
      :title="actionError"
      type="error"
      show-icon
      closable
      @close="actionError = ''"
    />
    <el-alert
      v-if="lastReport"
      class="page-alert"
      :title="reportTitle"
      :type="reportAlertType"
      show-icon
      closable
      @close="lastReport = null"
    />

    <div v-if="selectedIds.length" class="batch-toolbar">
      <span>已选择 {{ selectedIds.length }} 条待审核知识</span>
      <div class="batch-buttons">
        <el-tooltip
          content="选中项中存在尚未生成向量的知识，不能批量通过"
          placement="top"
          :disabled="!batchApproveBlocked"
        >
          <span class="action-button-wrapper">
            <el-button
              type="success"
              :icon="CircleCheck"
              :loading="batchReviewing === 'approve'"
              :disabled="batchApproveBlocked || Boolean(batchReviewing)"
              @click="reviewSelected('approve')"
            >批量通过</el-button>
          </span>
        </el-tooltip>
        <el-button
          type="danger"
          plain
          :icon="CircleClose"
          :loading="batchReviewing === 'reject'"
          :disabled="Boolean(batchReviewing)"
          @click="reviewSelected('reject')"
        >批量拒绝</el-button>
      </div>
    </div>

    <el-table
      ref="unitTableRef"
      v-loading="loading"
      :data="units"
      border
      stripe
      row-key="id"
      class="desktop-unit-table"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="48" :selectable="isSelectableUnit" />
      <el-table-column type="expand" width="48">
        <template #default="{ row }">
          <SemanticUnitDetails :unit="row" />
        </template>
      </el-table-column>
      <el-table-column prop="id" label="编号" width="78" />
      <el-table-column prop="documentId" label="文档 ID" width="100" />
      <el-table-column label="类型" width="92">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ unitTypeText(row.unitType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="问题" min-width="220">
        <template #default="{ row }">
          <span class="content-cell">{{ row.question || '未生成问题' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="陈述" min-width="300">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.statement"
            :content="row.statement"
            placement="top"
            :show-after="350"
          >
            <span class="content-cell content-clamped">{{ row.statement }}</span>
          </el-tooltip>
          <span v-else class="muted">未生成陈述</span>
        </template>
      </el-table-column>
      <el-table-column label="证据切片" min-width="170">
        <template #default="{ row }">
          <div v-if="evidenceIds(row).length" class="evidence-tags">
            <el-tag v-for="id in evidenceIds(row)" :key="id" size="small" effect="plain">
              #{{ id }}
            </el-tag>
          </div>
          <span v-else class="muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="置信度" width="105" align="center">
        <template #default="{ row }">
          <el-tag :type="confidenceTag(row.extractionConfidence)" size="small">
            {{ confidenceText(row.extractionConfidence) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="向量" width="92" align="center">
        <template #default="{ row }">
          <el-tag :type="row.embeddingReady ? 'success' : 'warning'" size="small">
            {{ row.embeddingReady ? '已生成' : '待生成' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="92" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="148" fixed="right">
        <template #default="{ row }">
          <div v-if="row.status === 'DRAFT'" class="row-actions">
            <el-tooltip
              content="向量尚未生成，不能通过审核"
              placement="top"
              :disabled="row.embeddingReady"
            >
              <span class="action-button-wrapper">
                <el-button
                  link
                  type="success"
                  :icon="CircleCheck"
                  :loading="isReviewing(row.id, 'approve')"
                  :disabled="!row.embeddingReady || isReviewing(row.id) || Boolean(batchReviewing)"
                  @click="reviewUnit(row, 'approve')"
                >通过</el-button>
              </span>
            </el-tooltip>
            <el-button
              link
              type="danger"
              :icon="CircleClose"
              :loading="isReviewing(row.id, 'reject')"
              :disabled="isReviewing(row.id) || Boolean(batchReviewing)"
              @click="reviewUnit(row, 'reject')"
            >拒绝</el-button>
          </div>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无结构化知识单元" />
      </template>
    </el-table>

    <div v-loading="loading" class="mobile-unit-list">
      <article v-for="unit in units" :key="unit.id" class="mobile-unit-item">
        <div class="mobile-unit-header">
          <div class="mobile-unit-title">
            <el-checkbox
              v-if="unit.status === 'DRAFT'"
              :model-value="isUnitSelected(unit.id)"
              :disabled="Boolean(batchReviewing)"
              :aria-label="`选择知识单元 ${unit.id}`"
              @change="toggleMobileSelection(unit, $event)"
            />
            <strong>{{ unit.question || '未生成问题' }}</strong>
          </div>
          <el-tag :type="statusTag(unit.status)" size="small">{{ statusText(unit.status) }}</el-tag>
        </div>

        <div class="mobile-tags">
          <el-tag size="small" type="info">{{ unitTypeText(unit.unitType) }}</el-tag>
          <el-tag :type="confidenceTag(unit.extractionConfidence)" size="small">
            置信度 {{ confidenceText(unit.extractionConfidence) }}
          </el-tag>
          <el-tag :type="unit.embeddingReady ? 'success' : 'warning'" size="small">
            {{ unit.embeddingReady ? '向量已生成' : '向量待生成' }}
          </el-tag>
        </div>

        <dl class="mobile-meta">
          <div><dt>编号</dt><dd>#{{ unit.id }}</dd></div>
          <div><dt>文档 ID</dt><dd>{{ unit.documentId }}</dd></div>
        </dl>

        <div class="mobile-statement">
          <span>陈述</span>
          <p>{{ unit.statement || '未生成陈述' }}</p>
        </div>

        <div class="mobile-evidence">
          <span>证据切片</span>
          <div v-if="evidenceIds(unit).length" class="evidence-tags">
            <el-tag v-for="id in evidenceIds(unit)" :key="id" size="small" effect="plain">
              #{{ id }}
            </el-tag>
          </div>
          <span v-else class="muted">无</span>
        </div>

        <el-collapse v-model="mobileExpanded" class="mobile-detail-collapse">
          <el-collapse-item title="核验详情" :name="String(unit.id)">
            <SemanticUnitDetails :unit="unit" />
          </el-collapse-item>
        </el-collapse>

        <div v-if="unit.status === 'DRAFT'" class="mobile-actions">
          <el-tooltip
            content="向量尚未生成，不能通过审核"
            placement="top"
            :disabled="unit.embeddingReady"
          >
            <span class="action-button-wrapper">
              <el-button
                type="success"
                :icon="CircleCheck"
                :loading="isReviewing(unit.id, 'approve')"
                :disabled="!unit.embeddingReady || isReviewing(unit.id) || Boolean(batchReviewing)"
                @click="reviewUnit(unit, 'approve')"
              >通过</el-button>
            </span>
          </el-tooltip>
          <el-button
            type="danger"
            plain
            :icon="CircleClose"
            :loading="isReviewing(unit.id, 'reject')"
            :disabled="isReviewing(unit.id) || Boolean(batchReviewing)"
            @click="reviewUnit(unit, 'reject')"
          >拒绝</el-button>
        </div>
      </article>
      <el-empty v-if="!loading && !units.length" description="暂无结构化知识单元" />
    </div>
  </el-card>

  <el-dialog
    v-model="extractionVisible"
    class="extraction-dialog"
    title="发起结构化抽取"
    width="520px"
    :close-on-click-modal="!extracting"
    :close-on-press-escape="!extracting"
  >
    <el-alert
      v-if="extractionError"
      class="dialog-alert"
      :title="extractionError"
      type="error"
      show-icon
      closable
      @close="extractionError = ''"
    />
    <el-form
      ref="extractionFormRef"
      :model="extractionForm"
      :rules="extractionRules"
      label-position="top"
    >
      <el-form-item prop="documentId">
        <template #label>
          <div class="document-field-label">
            <span>知识文档</span>
            <el-tooltip content="刷新文档列表" placement="top">
              <el-button
                class="document-refresh-button"
                link
                type="primary"
                :icon="Refresh"
                :loading="documentLoading"
                aria-label="刷新文档列表"
                @click="loadExtractionDocuments"
              />
            </el-tooltip>
          </div>
        </template>
        <el-select
          v-model="extractionForm.documentId"
          filterable
          clearable
          :loading="documentLoading"
          :disabled="documentLoading"
          loading-text="正在加载知识文档"
          :no-data-text="documentSelectEmptyText"
          no-match-text="未找到匹配文档"
          placeholder="搜索文件名、标题或文档 ID"
        >
          <el-option
            v-for="document in extractionDocuments"
            :key="document.id"
            :label="documentOptionLabel(document)"
            :value="document.id"
          >
            <div class="document-option">
              <span class="document-option-name">{{ documentDisplayName(document) }}</span>
              <span class="document-option-id">ID {{ document.id }}</span>
            </div>
          </el-option>
        </el-select>
        <p
          v-if="documentLoadError"
          class="document-list-state is-error"
          role="status"
        >{{ documentLoadError }}</p>
        <p
          v-else-if="documentListLoaded && !extractionDocuments.length"
          class="document-list-state"
          role="status"
        >暂无处理完成的知识文档</p>
      </el-form-item>
      <el-form-item label="Extraction 模型 ID（可选）" prop="preferredModelId">
        <el-input-number
          v-model="extractionForm.preferredModelId"
          :min="1"
          :controls="false"
          placeholder="留空时使用默认抽取模型"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="extracting" @click="extractionVisible = false">取消</el-button>
      <el-button
        type="primary"
        :icon="MagicStick"
        :loading="extracting"
        :disabled="documentLoading || Boolean(documentLoadError) || !extractionDocuments.length"
        @click="extractDocument"
      >
        开始抽取
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, onMounted } from 'vue'
import { CircleCheck, CircleClose, MagicStick, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/index.js'
import SemanticUnitDetails from './components/SemanticUnitDetails.vue'

const units = ref([])
const unitTableRef = ref()
const loading = ref(false)
const documentIdFilter = ref(null)
const statusFilter = ref('DRAFT')
const listError = ref('')
const actionError = ref('')
const reviewing = ref({})
const selectedIds = ref([])
const batchReviewing = ref('')
const mobileExpanded = ref([])
const lastReport = ref(null)

const extractionVisible = ref(false)
const extracting = ref(false)
const extractionError = ref('')
const extractionDocuments = ref([])
const documentLoading = ref(false)
const documentListLoaded = ref(false)
const documentLoadError = ref('')
const extractionFormRef = ref()
const extractionForm = reactive({ documentId: null, preferredModelId: null })
const extractionRules = {
  documentId: [{ required: true, message: '请选择知识文档', trigger: 'change' }]
}
let documentLoadSequence = 0

const documentSelectEmptyText = computed(() => (
  documentLoadError.value ? '文档加载失败' : '暂无可抽取的知识文档'
))

const reportAlertType = computed(() => ({
  SUCCESS: 'success',
  PARTIAL: 'warning',
  UNAVAILABLE: 'warning',
  FAILED: 'error'
}[lastReport.value?.status] || 'info'))

const reportTitle = computed(() => {
  const report = lastReport.value
  if (!report) return ''
  const counts = report.status === 'SUCCESS' || report.status === 'PARTIAL'
    ? `，生成 ${report.validatedUnits || 0} 条，向量完成 ${report.embeddedUnits || 0} 条`
    : ''
  return `${report.message || '抽取任务已结束'}${counts}`
})

const selectedUnits = computed(() => {
  const ids = new Set(selectedIds.value)
  return units.value.filter(unit => ids.has(unit.id))
})

const batchApproveBlocked = computed(() => (
  selectedUnits.value.some(unit => !unit.embeddingReady)
))

async function loadUnits() {
  clearSelection()
  loading.value = true
  listError.value = ''
  try {
    const params = {}
    if (documentIdFilter.value) params.documentId = documentIdFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const response = await request.get('/admin/knowledge/semantic-unit/list', { params })
    units.value = Array.isArray(response.data) ? response.data : []
  } catch (error) {
    units.value = []
    listError.value = errorMessage(error, '结构化知识加载失败')
  } finally {
    loading.value = false
  }
}

function clearSelection() {
  selectedIds.value = []
  unitTableRef.value?.clearSelection()
}

function isSelectableUnit(unit) {
  return unit?.status === 'DRAFT'
}

function handleSelectionChange(rows) {
  selectedIds.value = rows
    .filter(isSelectableUnit)
    .map(unit => unit.id)
}

function isUnitSelected(unitId) {
  return selectedIds.value.includes(unitId)
}

function toggleMobileSelection(unit, selected) {
  if (!isSelectableUnit(unit)) return
  if (unitTableRef.value) {
    unitTableRef.value.toggleRowSelection(unit, Boolean(selected))
    return
  }
  const ids = new Set(selectedIds.value)
  if (selected) ids.add(unit.id)
  else ids.delete(unit.id)
  selectedIds.value = [...ids]
}

function resetFilters() {
  documentIdFilter.value = null
  statusFilter.value = 'DRAFT'
  loadUnits()
}

function openExtraction() {
  extractionForm.documentId = normalizeDocumentId(documentIdFilter.value)
  extractionForm.preferredModelId = null
  extractionError.value = ''
  extractionDocuments.value = []
  documentLoadError.value = ''
  documentListLoaded.value = false
  extractionVisible.value = true
  nextTick(() => extractionFormRef.value?.clearValidate())
  loadExtractionDocuments()
}

async function loadExtractionDocuments() {
  const sequence = ++documentLoadSequence
  documentLoading.value = true
  documentLoadError.value = ''
  try {
    const response = await request.get('/admin/doc/list', {
      params: { page: 1, size: 200 }
    })
    if (sequence !== documentLoadSequence) return

    const data = response?.data
    const records = Array.isArray(data)
      ? data
      : Array.isArray(data?.records) ? data.records : []
    extractionDocuments.value = eligibleExtractionDocuments(records)

    const selectedId = normalizeDocumentId(extractionForm.documentId)
    extractionForm.documentId = extractionDocuments.value.some(doc => doc.id === selectedId)
      ? selectedId
      : null
  } catch (error) {
    if (sequence !== documentLoadSequence) return
    extractionDocuments.value = []
    extractionForm.documentId = null
    documentLoadError.value = errorMessage(error, '知识文档加载失败，请刷新重试')
  } finally {
    if (sequence === documentLoadSequence) {
      documentLoading.value = false
      documentListLoaded.value = true
    }
  }
}

function eligibleExtractionDocuments(records) {
  const documents = new Map()
  records.forEach(document => {
    const id = normalizeDocumentId(document?.id)
    if (!id) return

    const hasStatus = document.status !== undefined
      && document.status !== null
      && document.status !== ''
    if (hasStatus && Number(document.status) !== 2) return

    const sourceScope = String(document.sourceScope ?? '').trim()
    if (sourceScope && sourceScope.toUpperCase() !== 'KNOWLEDGE') return
    documents.set(id, { ...document, id })
  })
  return [...documents.values()]
}

function normalizeDocumentId(value) {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function documentDisplayName(document) {
  const title = String(document?.title ?? '').trim()
  const fileName = String(document?.fileName ?? '').trim()
  if (title && fileName && title !== fileName) return `${title}（${fileName}）`
  return title || fileName || '未命名文档'
}

function documentOptionLabel(document) {
  return `${documentDisplayName(document)} · ID ${document.id}`
}

async function extractDocument() {
  if (!extractionFormRef.value || extracting.value) return
  const valid = await extractionFormRef.value.validate().catch(() => false)
  if (!valid) return
  const documentId = normalizeDocumentId(extractionForm.documentId)
  if (!documentId) return

  extracting.value = true
  extractionError.value = ''
  try {
    const payload = extractionForm.preferredModelId
      ? { preferredModelId: extractionForm.preferredModelId }
      : {}
    const response = await request.post(
      `/admin/knowledge/semantic-unit/extract/${documentId}`,
      payload,
      { timeout: 1800000 }
    )
    const report = response.data || {}
    lastReport.value = report

    if (report.status === 'FAILED') {
      extractionError.value = report.message || '结构化抽取失败'
      return
    }
    if (report.status === 'UNAVAILABLE') {
      extractionError.value = report.message || '没有可用的 Extraction 模型'
      return
    }

    documentIdFilter.value = documentId
    statusFilter.value = 'DRAFT'
    extractionVisible.value = false
    if (report.status === 'PARTIAL') {
      ElMessage.warning(report.message || '部分抽取批次失败')
    } else {
      ElMessage.success(report.message || '结构化知识已生成')
    }
    await loadUnits()
  } catch (error) {
    extractionError.value = errorMessage(error, '结构化抽取失败')
  } finally {
    extracting.value = false
  }
}

async function reviewUnit(unit, action) {
  if (isReviewing(unit.id) || batchReviewing.value) return
  if (action === 'approve' && !unit.embeddingReady) {
    ElMessage.warning('向量尚未生成，不能通过审核')
    return
  }
  reviewing.value = { ...reviewing.value, [unit.id]: action }
  actionError.value = ''
  try {
    const { value } = await ElMessageBox.prompt(
      action === 'approve' ? '可填写本次审核备注' : '请填写拒绝原因',
      action === 'approve' ? '审核通过' : '拒绝知识单元',
      {
        confirmButtonText: action === 'approve' ? '确认通过' : '确认拒绝',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: action === 'approve' ? '审核备注（可选）' : '拒绝原因',
        inputValidator: input => {
          const normalized = String(input || '').trim()
          if (action === 'reject' && !normalized) return '请输入拒绝原因'
          if (normalized.length > 500) return '审核备注不能超过 500 个字符'
          return true
        }
      }
    )
    await request.post(`/admin/knowledge/semantic-unit/${unit.id}/${action}`, {
      reason: String(value || '').trim() || null
    })
    ElMessage.success(action === 'approve' ? '结构化知识已通过' : '结构化知识已拒绝')
    await loadUnits()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    actionError.value = errorMessage(
      error,
      action === 'approve' ? '审核通过失败' : '拒绝操作失败'
    )
  } finally {
    const next = { ...reviewing.value }
    delete next[unit.id]
    reviewing.value = next
  }
}

async function reviewSelected(action) {
  const unitIds = [...selectedIds.value]
  if (!unitIds.length || batchReviewing.value) return
  if (action === 'approve' && batchApproveBlocked.value) {
    ElMessage.warning('选中项中存在尚未生成向量的知识，请取消选择后重试')
    return
  }

  let reason
  try {
    const result = await ElMessageBox.prompt(
      action === 'approve'
        ? `将通过选中的 ${unitIds.length} 条结构化知识，可填写审核备注`
        : `将拒绝选中的 ${unitIds.length} 条结构化知识，请填写拒绝原因`,
      action === 'approve' ? '批量通过' : '批量拒绝',
      {
        confirmButtonText: action === 'approve' ? '确认批量通过' : '确认批量拒绝',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: action === 'approve' ? '审核备注（可选）' : '拒绝原因',
        inputValidator: input => {
          const normalized = String(input || '').trim()
          if (action === 'reject' && !normalized) return '请输入拒绝原因'
          if (normalized.length > 500) return '审核备注不能超过 500 个字符'
          return true
        }
      }
    )
    reason = String(result.value || '').trim() || null
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    actionError.value = errorMessage(error, '无法打开批量审核确认框')
    return
  }

  batchReviewing.value = action
  actionError.value = ''
  try {
    const response = await request.post(
      `/admin/knowledge/semantic-unit/batch/${action}`,
      { unitIds, reason }
    )
    const report = response.data || {}
    const failedItems = Array.isArray(report.items)
      ? report.items.filter(item => !item.success)
      : []
    const details = failedItems
      .slice(0, 5)
      .map(item => `#${item.unitId}：${item.error || '审核失败'}`)
    if (failedItems.length > 5) details.push(`另有 ${failedItems.length - 5} 条失败`)
    if (!report.indexSyncSuccess) {
      details.push(`索引同步失败：${report.indexSyncError || '未知错误'}`)
    }
    actionError.value = details.join('；')

    if (Number(report.failed) > 0 || !report.indexSyncSuccess) {
      ElMessage.warning(`批量审核完成：成功 ${report.succeeded || 0} 条，失败 ${report.failed || 0} 条`)
    } else {
      ElMessage.success(
        action === 'approve'
          ? `已通过 ${report.succeeded || 0} 条结构化知识`
          : `已拒绝 ${report.succeeded || 0} 条结构化知识`
      )
    }
    await loadUnits()
  } catch (error) {
    actionError.value = errorMessage(
      error,
      action === 'approve' ? '批量通过失败' : '批量拒绝失败'
    )
  } finally {
    batchReviewing.value = ''
  }
}

function isReviewing(id, action) {
  return action ? reviewing.value[id] === action : Boolean(reviewing.value[id])
}

function evidenceIds(unit) {
  const value = unit?.evidenceChunkIdsJson
  if (Array.isArray(value)) return value.filter(id => id !== null && id !== '')
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter(id => id !== null && id !== '') : []
  } catch {
    return []
  }
}

function confidenceText(value) {
  const confidence = Number(value)
  if (!Number.isFinite(confidence)) return '未知'
  return `${Math.round(Math.max(0, Math.min(1, confidence)) * 100)}%`
}

function confidenceTag(value) {
  const confidence = Number(value)
  if (!Number.isFinite(confidence)) return 'info'
  if (confidence >= 0.85) return 'success'
  if (confidence >= 0.65) return 'warning'
  return 'danger'
}

function statusTag(status) {
  return { DRAFT: 'warning', APPROVED: 'success', REJECTED: 'danger' }[status] || 'info'
}

function statusText(status) {
  return { DRAFT: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' }[status] || '未知'
}

function unitTypeText(type) {
  return { QA: '问答', FACT: '事实', PROCEDURE: '流程', POLICY: '政策' }[type] || type || '未知'
}

function errorMessage(error, fallback) {
  const message = error?.response?.data?.msg || error?.message
  return message && message !== 'Request failed with status code 500' ? message : fallback
}

onMounted(loadUnits)
</script>

<style scoped>
.page-header,
.filters,
.batch-toolbar,
.batch-buttons,
.row-actions,
.evidence-tags,
.mobile-unit-header,
.mobile-unit-title,
.mobile-tags,
.mobile-actions { display:flex; align-items:center; gap:8px; }
.page-header { justify-content:space-between; font-weight:600; }
.filters { flex-wrap:wrap; margin-bottom:14px; }
.filters .el-input-number { width:180px; }
.filters .el-select { width:150px; }
.page-alert { margin-bottom:12px; }
.batch-toolbar { justify-content:space-between; min-height:48px; margin-bottom:12px; padding:8px 12px; background:#f5f7fa; border:1px solid #dcdfe6; border-radius:4px; }
.batch-toolbar > span { color:#606266; font-size:14px; }
.batch-buttons { flex:none; }
.content-cell { display:block; line-height:1.55; overflow-wrap:anywhere; }
.content-clamped { display:-webkit-box; overflow:hidden; -webkit-box-orient:vertical; -webkit-line-clamp:3; }
.evidence-tags { flex-wrap:wrap; }
.row-actions { justify-content:center; gap:2px; }
.action-button-wrapper { display:inline-flex; }
.muted { color:#909399; }
.mobile-unit-list { display:none; min-height:80px; }
.dialog-alert { margin-bottom:16px; }
:deep(.extraction-dialog) { max-width:calc(100vw - 24px); }
:deep(.extraction-dialog .el-input-number),
:deep(.extraction-dialog .el-select) { width:100%; }
.document-field-label { display:flex; align-items:center; justify-content:space-between; width:100%; }
.document-refresh-button { width:28px; height:28px; padding:0; }
.document-option { display:flex; align-items:center; justify-content:space-between; gap:12px; min-width:0; }
.document-option-name { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.document-option-id { flex:none; color:#909399; font-size:12px; font-variant-numeric:tabular-nums; }
.document-list-state { width:100%; margin:6px 0 0; color:#909399; font-size:12px; line-height:1.5; }
.document-list-state.is-error { color:#f56c6c; }

@media (max-width: 760px) {
  .semantic-units-page :deep(.el-card__header) { padding:14px 12px; }
  .semantic-units-page :deep(.el-card__body) { padding:12px; }
  .page-header { flex-wrap:wrap; }
  .filters { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); }
  .filters .el-input-number,
  .filters .el-select { width:100%; }
  .filters .el-button { width:100%; margin:0; }
  .desktop-unit-table { display:none; }
  .batch-toolbar { align-items:stretch; flex-direction:column; }
  .batch-buttons { display:grid; grid-template-columns:1fr 1fr; }
  .batch-buttons .el-button,
  .batch-buttons .action-button-wrapper,
  .batch-buttons .action-button-wrapper .el-button { width:100%; margin:0; }
  .mobile-unit-list { display:block; }
  .mobile-unit-item { padding:16px 0; border-top:1px solid #ebeef5; }
  .mobile-unit-item:last-child { border-bottom:1px solid #ebeef5; }
  .mobile-unit-header { align-items:flex-start; justify-content:space-between; }
  .mobile-unit-title { align-items:flex-start; min-width:0; }
  .mobile-unit-title strong { min-width:0; line-height:1.5; overflow-wrap:anywhere; }
  .mobile-unit-title .el-checkbox { flex:none; height:24px; }
  .mobile-unit-header .el-tag { flex:none; }
  .mobile-tags { flex-wrap:wrap; margin-top:10px; }
  .mobile-meta { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin:14px 0; }
  .mobile-meta dt { margin-bottom:3px; color:#909399; font-size:12px; }
  .mobile-meta dd { margin:0; overflow-wrap:anywhere; }
  .mobile-statement > span,
  .mobile-evidence > span:first-child { display:block; margin-bottom:5px; color:#909399; font-size:12px; }
  .mobile-statement p { margin:0; line-height:1.6; white-space:pre-wrap; overflow-wrap:anywhere; }
  .mobile-evidence { margin-top:12px; }
  .mobile-detail-collapse { margin-top:10px; }
  :deep(.mobile-detail-collapse .el-collapse-item__header) { height:42px; color:#337ecc; font-size:13px; }
  :deep(.mobile-detail-collapse .el-collapse-item__content) { padding-bottom:0; }
  .mobile-actions { justify-content:flex-end; margin-top:16px; }
}
</style>
