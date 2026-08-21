<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>未命中问题收集</span>
        <div class="header-actions">
          <el-tag type="info">智能试聊中知识库无法回答的问题会自动记录</el-tag>
          <el-button type="primary" :loading="clustering" @click="runClustering">聚类分析</el-button>
        </div>
      </div>
    </template>
    <el-table v-loading="loading" :data="questions" border stripe>
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column prop="question" label="问题" min-width="300" show-overflow-tooltip />
      <el-table-column prop="similarCount" label="出现次数" width="100" align="center">
        <template #default="{row}"><el-tag size="small" :type="row.similarCount>1?'warning':'info'">{{ row.similarCount }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="isResolved" label="状态" width="100">
        <template #default="{row}">
          <el-tag size="small" :type="row.isResolved===1?'success':'danger'">{{ row.isResolved===1?'已处理':'待处理' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="180">
        <template #default="{row}">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{row}">
          <el-button v-if="row.isResolved!==1" size="small" type="primary" @click="resolve(row.id)">标记已处理</el-button>
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

  <el-card v-if="clusterResult" class="cluster-card">
    <template #header>
      <div class="cluster-summary">
        <span>相似问题聚类</span>
        <span class="summary-items">
          <el-tag size="small">问题 {{ clusterResult.questionCount }}</el-tag>
          <el-tag size="small" type="success">聚类 {{ clusterResult.clusterCount }}</el-tag>
          <el-tag size="small" type="info">零散 {{ clusterResult.noiseCount }}</el-tag>
          <el-tag v-if="clusterResult.questionCount === 0" size="small" type="info">暂无待分析问题</el-tag>
          <el-tag v-else size="small" :type="clusterResult.embeddingUsed ? 'success' : 'warning'">
            {{ clusterResult.embeddingUsed ? '已使用语义向量' : '文本相似度降级' }}
          </el-tag>
          <el-button
            size="small"
            type="primary"
            :disabled="selectedClusterIds.length < 2"
            @click="mergeSelectedClusters"
          >合并所选聚类</el-button>
        </span>
      </div>
    </template>
    <el-alert
      v-if="clusterResult.questionCount > 0 && !clusterResult.embeddingUsed"
      title="当前未检测到可用的向量模型，结果使用文本相似度，仅供初步整理。"
      type="warning"
      :closable="false"
      show-icon
      class="cluster-alert"
    />
    <el-empty
      v-if="clusterResult.clusters.length === 0"
      :description="clusterResult.questionCount === 0 ? '当前没有待分析的未命中问题' : '暂未形成至少两个问题的聚类'"
    />
    <el-collapse v-else>
      <el-collapse-item v-for="cluster in clusterResult.clusters" :key="cluster.id" :name="cluster.id">
        <template #title>
          <div class="cluster-title">
            <el-checkbox
              v-if="cluster.ignored !== 1"
              :model-value="selectedClusterIds.includes(cluster.id)"
              @click.stop
              @change="toggleClusterSelection(cluster.id, $event)"
            />
            <span class="cluster-name" :class="{ 'is-ignored': cluster.ignored === 1 }">{{ cluster.title }}</span>
            <span class="cluster-meta">
              {{ cluster.questionCount }} 个问题 · {{ cluster.totalOccurrences }} 次出现 · 相似度 {{ formatScore(cluster.cohesion) }}
            </span>
            <span class="cluster-actions" @click.stop>
              <el-tag v-if="cluster.ignored === 1" size="small" type="info">已忽略</el-tag>
              <el-tag
                v-if="draftFor(cluster.id)"
                size="small"
                :type="draftStatusType(draftFor(cluster.id).status)"
              >{{ draftStatusLabel(draftFor(cluster.id).status) }}</el-tag>
              <el-button size="small" text type="primary" @click="renameCluster(cluster)">修改标题</el-button>
              <el-button v-if="cluster.ignored !== 1" size="small" text type="warning" @click="ignoreCluster(cluster)">忽略</el-button>
              <el-button v-if="cluster.ignored !== 1" size="small" text type="danger" @click="deleteCluster(cluster)">删除</el-button>
              <el-button
                v-if="cluster.ignored !== 1"
                size="small"
                text
                type="success"
                :loading="generatingDraftId === cluster.id"
                @click="openFaqDraft(cluster)"
              >{{ draftFor(cluster.id) ? '查看FAQ草稿' : '生成FAQ草稿' }}</el-button>
              <el-button
                v-if="cluster.ignored !== 1 && splitSelectionFor(cluster).length > 0 && splitSelectionFor(cluster).length < cluster.questions.length"
                size="small"
                text
                type="primary"
                @click="splitCluster(cluster)"
              >拆分选中</el-button>
            </span>
          </div>
        </template>
        <el-table
          :data="cluster.questions"
          size="small"
          border
          @selection-change="rows => setSplitSelection(cluster.id, rows)"
        >
          <el-table-column type="selection" width="48" :selectable="() => cluster.ignored !== 1" />
          <el-table-column prop="question" label="原始问题" min-width="260" show-overflow-tooltip />
          <el-table-column prop="analysisQuestion" label="分析文本" min-width="220" show-overflow-tooltip />
          <el-table-column prop="similarCount" label="出现次数" width="90" align="center" />
          <el-table-column label="与代表问题相似度" width="150" align="center">
            <template #default="{ row }">{{ formatScore(row.similarityToTitle) }}</template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
    <div v-if="clusterResult.noiseQuestions.length" class="noise-block">
      <div class="noise-title">零散问题（展示前 {{ clusterResult.noiseQuestions.length }} 条）</div>
      <el-tag v-for="item in clusterResult.noiseQuestions" :key="item.id" class="noise-tag" type="info">
        {{ item.question }}
      </el-tag>
    </div>
  </el-card>

  <el-dialog
    v-model="draftDialogVisible"
    title="FAQ草稿审核"
    width="min(820px, 94vw)"
    destroy-on-close
  >
    <template v-if="activeDraft">
      <div class="draft-status-row">
        <el-tag :type="draftStatusType(activeDraft.status)">{{ draftStatusLabel(activeDraft.status) }}</el-tag>
        <el-tag :type="activeDraft.evidenceStatus === 'SUPPORTED' ? 'success' : 'warning'">
          {{ evidenceStatusLabel(activeDraft.evidenceStatus) }}
        </el-tag>
        <el-tag v-if="activeDraft.status === 'PUBLISHED'" type="info">
          已命中 {{ activeDraft.publishedHitCount || 0 }} 次
        </el-tag>
      </div>
      <el-alert
        v-if="activeDraft.generationMessage"
        :title="activeDraft.generationMessage"
        :type="activeDraft.evidenceStatus === 'SUPPORTED' && activeDraft.answer ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="draft-alert"
      />
      <el-alert
        v-if="activeDraft.duplicateItemId"
        :title="`发现相似FAQ #${activeDraft.duplicateItemId}，相似度 ${formatScore(activeDraft.duplicateScore)}`"
        type="warning"
        :closable="false"
        show-icon
        class="draft-alert"
      />
      <el-form label-position="top">
        <el-form-item label="标准问题">
          <el-input
            v-model="draftForm.question"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
        <el-form-item label="答案草稿">
          <el-input
            v-model="draftForm.answer"
            type="textarea"
            :rows="7"
            maxlength="20000"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
        <el-form-item label="关键词与相似问法">
          <el-input
            v-model="draftForm.keywords"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
      </el-form>
      <div v-if="activeDraft.similarQuestions?.length" class="draft-section">
        <div class="draft-section-title">客户真实问法</div>
        <el-tag
          v-for="question in activeDraft.similarQuestions"
          :key="question"
          type="info"
          class="question-tag"
        >{{ question }}</el-tag>
      </div>
      <div class="draft-section">
        <div class="draft-section-title">知识依据</div>
        <el-empty v-if="!activeDraft.evidence?.length" description="没有找到可用于发布的知识依据" :image-size="64" />
        <el-table v-else :data="activeDraft.evidence" size="small" border>
          <el-table-column prop="title" label="来源" min-width="180" />
          <el-table-column prop="snippet" label="内容摘要" min-width="320" show-overflow-tooltip />
          <el-table-column label="相关度" width="90" align="center">
            <template #default="{ row }">{{ formatScore(row.score) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <el-alert
        v-if="activeDraft.status === 'REJECTED' && activeDraft.reviewReason"
        :title="`拒绝原因：${activeDraft.reviewReason}`"
        type="error"
        :closable="false"
        class="draft-alert"
      />
    </template>
    <template #footer>
      <el-button @click="draftDialogVisible = false">关闭</el-button>
      <el-button
        v-if="activeDraft?.status !== 'PUBLISHED'"
        :loading="draftSaving"
        @click="regenerateDraft"
      >重新生成</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="danger"
        plain
        :disabled="draftSaving"
        @click="rejectFaqDraft"
      >拒绝</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="primary"
        plain
        :loading="draftSaving"
        @click="saveFaqDraft"
      >保存草稿</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="success"
        :loading="draftPublishing"
        :disabled="!canPublishDraft"
        @click="publishFaqDraft"
      >审核并发布</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { ref, onMounted, computed } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatDateTime } from '../../utils/displayText.js'
const questions = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = 10
const clustering = ref(false)
const clusterResult = ref(null)
const selectedClusterIds = ref([])
const splitSelections = ref({})
const faqDrafts = ref([])
const generatingDraftId = ref(null)
const draftDialogVisible = ref(false)
const activeDraft = ref(null)
const activeDraftCluster = ref(null)
const draftForm = ref({ question: '', answer: '', keywords: '' })
const draftSaving = ref(false)
const draftPublishing = ref(false)
const faqDraftTimeout = 180000
const canPublishDraft = computed(() => activeDraft.value?.status === 'DRAFT'
  && activeDraft.value?.evidenceStatus === 'SUPPORTED'
  && draftForm.value.question.trim()
  && draftForm.value.answer.trim())

async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/unmatched/list', { params: { page: page.value, size: pageSize } })
    questions.value = r.data?.records || []
    total.value = r.data?.total || 0
  } catch {
    questions.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function resolve(id) {
  await request.put('/admin/unmatched/' + id + '/resolve')
  ElMessage.success('已标记'); fetch()
}

async function runClustering() {
  clustering.value = true
  try {
    const response = await request.post('/admin/unmatched/cluster/run', null, {
      params: { limit: 500, threshold: 0.82, minClusterSize: 2 }
    })
    clusterResult.value = response.data || null
    clearReviewSelections()
    await fetchFaqDrafts()
    ElMessage.success('聚类审核批次已生成')
  } finally {
    clustering.value = false
  }
}

function formatScore(value) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

async function fetchLatestCluster() {
  try {
    const response = await request.get('/admin/unmatched/cluster/list')
    clusterResult.value = response.data || null
    clearReviewSelections()
    await fetchFaqDrafts()
  } catch {
    clusterResult.value = null
    faqDrafts.value = []
  }
}

async function fetchFaqDrafts() {
  const runId = clusterResult.value?.runId
  if (!runId) {
    faqDrafts.value = []
    return
  }
  const response = await request.get('/admin/unmatched/faq-draft/list', { params: { runId } })
  faqDrafts.value = response.data || []
}

function draftFor(clusterId) {
  return faqDrafts.value.find(item => item.clusterId === clusterId)
}

function draftStatusLabel(status) {
  return ({ DRAFT: '待审核', REJECTED: '已拒绝', PUBLISHED: '已发布' })[status] || status || '未知'
}

function draftStatusType(status) {
  return ({ DRAFT: 'warning', REJECTED: 'danger', PUBLISHED: 'success' })[status] || 'info'
}

function evidenceStatusLabel(status) {
  return ({ SUPPORTED: '知识依据充足', MISSING: '知识依据不足', STALE: '知识依据待更新' })[status] || '知识依据未知'
}

function setActiveDraft(draft, cluster = activeDraftCluster.value) {
  activeDraft.value = draft
  activeDraftCluster.value = cluster
  draftForm.value = {
    question: draft?.question || '',
    answer: draft?.answer || '',
    keywords: draft?.keywords || ''
  }
  draftDialogVisible.value = Boolean(draft)
}

function replaceDraft(draft) {
  const next = faqDrafts.value.filter(item => item.id !== draft.id)
  faqDrafts.value = [draft, ...next]
  setActiveDraft(draft)
}

async function openFaqDraft(cluster) {
  if (generatingDraftId.value !== null) return
  const existing = draftFor(cluster.id)
  if (existing) {
    setActiveDraft(existing, cluster)
    return
  }
  activeDraftCluster.value = cluster
  generatingDraftId.value = cluster.id
  try {
    const response = await request.post(`/admin/unmatched/cluster/${cluster.id}/faq-draft`, null, { timeout: faqDraftTimeout })
    replaceDraft(response.data)
    ElMessage.success(response.data?.answer ? 'FAQ草稿已生成' : '草稿已建立，请检查知识依据')
  } finally {
    generatingDraftId.value = null
  }
}

async function regenerateDraft() {
  const clusterId = activeDraftCluster.value?.id || activeDraft.value?.clusterId
  if (!clusterId || draftSaving.value) return
  draftSaving.value = true
  try {
    const response = await request.post(`/admin/unmatched/cluster/${clusterId}/faq-draft`, null, {
      params: { regenerate: true },
      timeout: faqDraftTimeout
    })
    replaceDraft(response.data)
    ElMessage.success(response.data?.answer ? 'FAQ草稿已重新生成' : '已重新检查知识依据')
  } finally {
    draftSaving.value = false
  }
}

async function saveFaqDraft() {
  if (!activeDraft.value || draftSaving.value) return
  if (!draftForm.value.question.trim()) {
    ElMessage.warning('标准问题不能为空')
    return
  }
  draftSaving.value = true
  try {
    const response = await request.put(`/admin/unmatched/faq-draft/${activeDraft.value.id}`, {
      question: draftForm.value.question,
      answer: draftForm.value.answer,
      keywords: draftForm.value.keywords
    })
    replaceDraft(response.data)
    ElMessage.success('草稿已保存')
  } finally {
    draftSaving.value = false
  }
}

async function rejectFaqDraft() {
  if (!activeDraft.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝FAQ草稿', {
      inputValidator: value => value && value.trim() ? true : '拒绝原因不能为空',
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消'
    })
    const response = await request.post(`/admin/unmatched/faq-draft/${activeDraft.value.id}/reject`, {
      reason: value
    })
    replaceDraft(response.data)
    ElMessage.success('FAQ草稿已拒绝')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function publishFaqDraft() {
  if (!activeDraft.value || !canPublishDraft.value || draftPublishing.value) return
  try {
    await ElMessageBox.confirm(
      '发布后该FAQ会进入知识库并参与智能客服回答。',
      '审核并发布',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
    )
    draftPublishing.value = true
    await saveFaqDraft()
    if (activeDraft.value?.evidenceStatus !== 'SUPPORTED') {
      ElMessage.warning('标准问题已修改，请重新生成并确认知识依据')
      return
    }
    const response = await request.post(`/admin/unmatched/faq-draft/${activeDraft.value.id}/publish`, null, {
      timeout: faqDraftTimeout
    })
    replaceDraft(response.data)
    await fetch()
    ElMessage.success('FAQ已发布并完成向量索引')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    draftPublishing.value = false
  }
}

function clearReviewSelections() {
  selectedClusterIds.value = []
  splitSelections.value = {}
}

function toggleClusterSelection(id, checked) {
  const selected = new Set(selectedClusterIds.value)
  if (checked) selected.add(id)
  else selected.delete(id)
  selectedClusterIds.value = [...selected]
}

function setSplitSelection(id, rows) {
  splitSelections.value[id] = rows || []
}

function splitSelectionFor(cluster) {
  return splitSelections.value[cluster.id] || []
}

async function mergeSelectedClusters() {
  const selected = selectedClusterIds.value
  if (selected.length < 2) return
  try {
    await ElMessageBox.confirm(
      '将保留第一个选中聚类的标题，并把其他选中聚类的成员复制到其中。',
      '合并聚类',
      { type: 'warning', confirmButtonText: '确认合并', cancelButtonText: '取消' }
    )
    await request.post('/admin/unmatched/cluster/merge', {
      targetId: selected[0], sourceIds: selected.slice(1)
    })
    ElMessage.success('聚类已合并')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function splitCluster(cluster) {
  const selected = splitSelectionFor(cluster)
  if (!selected.length || selected.length >= cluster.questions.length) return
  try {
    const { value } = await ElMessageBox.prompt('可选填写新聚类标题', '拆分聚类', {
      inputValue: selected[0]?.question || '',
      confirmButtonText: '确认拆分',
      cancelButtonText: '取消'
    })
    await request.post(`/admin/unmatched/cluster/${cluster.id}/split`, {
      questionIds: selected.map(item => item.id), title: value
    })
    ElMessage.success('聚类已拆分')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function renameCluster(cluster) {
  try {
    const { value } = await ElMessageBox.prompt('请输入聚类标题', '修改聚类标题', {
      inputValue: cluster.title,
      inputValidator: value => value && value.trim() ? true : '标题不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    await request.put(`/admin/unmatched/cluster/${cluster.id}/title`, { title: value })
    cluster.title = value.trim()
    ElMessage.success('标题已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function ignoreCluster(cluster) {
  try {
    await ElMessageBox.confirm('忽略后仅从审核列表中标记，不会删除原始问题。', '忽略聚类', {
      type: 'warning', confirmButtonText: '确认忽略', cancelButtonText: '取消'
    })
    await request.put(`/admin/unmatched/cluster/${cluster.id}/ignore`)
    cluster.ignored = 1
    ElMessage.success('已忽略该聚类')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function deleteCluster(cluster) {
  try {
    await ElMessageBox.confirm(
      '删除后只会移除当前聚类审核记录及未发布的FAQ草稿，原始未命中问题不会删除。',
      '删除聚类',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
    await request.delete(`/admin/unmatched/cluster/${cluster.id}`)
    if (activeDraftCluster.value?.id === cluster.id) {
      draftDialogVisible.value = false
      activeDraft.value = null
      activeDraftCluster.value = null
    }
    ElMessage.success('聚类已删除')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

onMounted(() => {
  fetch()
  fetchLatestCluster()
})
</script>
<style scoped>
.header-actions { display:flex; align-items:center; gap:10px; }
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
.cluster-card { margin-top:16px; }
.cluster-summary { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.summary-items { display:flex; flex-wrap:wrap; gap:8px; justify-content:flex-end; }
.cluster-alert { margin-bottom:12px; }
.cluster-title { display:flex; align-items:center; flex-wrap:wrap; gap:12px; min-width:0; width:100%; padding-right:12px; }
.cluster-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cluster-meta { color:#8492a6; font-size:12px; white-space:nowrap; }
.cluster-actions { margin-left:auto; display:flex; flex-wrap:wrap; justify-content:flex-end; gap:6px; }
.is-ignored { color:#909399; }
.noise-block { margin-top:18px; padding-top:14px; border-top:1px solid #ebeef5; }
.noise-title { color:#606266; font-size:13px; margin-bottom:10px; }
.noise-tag { margin:0 8px 8px 0; max-width:100%; }
.draft-status-row { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px; }
.draft-alert { margin-bottom:12px; }
.draft-section { margin-top:18px; }
.draft-section-title { color:#303133; font-weight:600; margin-bottom:10px; }
.question-tag { margin:0 8px 8px 0; max-width:100%; white-space:normal; height:auto; }
@media (max-width: 720px) {
  .header-actions, .cluster-summary { align-items:flex-start; flex-direction:column; }
  .summary-items { justify-content:flex-start; }
  .cluster-title { display:block; }
  .cluster-meta { display:block; margin-top:4px; }
  .cluster-actions { margin-top:6px; margin-left:0; }
}
</style>
