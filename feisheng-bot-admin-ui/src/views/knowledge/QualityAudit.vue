<template>
  <section class="audit-page">
    <header class="page-header">
      <div>
        <h1>知识质量审计</h1>
        <p v-if="report">{{ report.documentCount }} 份生效文档 · {{ report.qaCount }} 组已审批问答</p>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAudit">
        重新审计
      </el-button>
    </header>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      closable
      @close="errorText = ''"
    />

    <div v-loading="loading" class="audit-content">
      <div class="summary-grid">
        <div class="summary-item blocker"><span>阻断项</span><strong>{{ severityCount('BLOCKER') }}</strong></div>
        <div class="summary-item high"><span>高风险</span><strong>{{ severityCount('HIGH') }}</strong></div>
        <div class="summary-item review"><span>待复核</span><strong>{{ severityCount('REVIEW') }}</strong></div>
        <div class="summary-item total"><span>全部发现</span><strong>{{ report?.findingCount || 0 }}</strong></div>
      </div>

      <div class="filters">
        <el-segmented v-model="severity" :options="severityOptions" />
        <el-select v-model="category" clearable placeholder="全部类别" aria-label="按类别筛选">
          <el-option v-for="value in categories" :key="value" :label="categoryText(value)" :value="value" />
        </el-select>
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索问题、答案或切片编号" />
      </div>

      <el-table :data="filteredFindings" border stripe row-key="key" class="desktop-table">
        <el-table-column type="expand" width="48">
          <template #default="{ row }">
            <div class="evidence-list">
              <article v-for="item in row.evidence" :key="item.chunkId" class="evidence-item">
                <div class="evidence-heading">
                  <strong>切片 #{{ item.chunkId }}</strong>
                  <span>{{ item.documentTitle || `文档 ${item.documentId}` }}</span>
                  <el-tag v-if="item.directAnswerEnabled === 1" type="danger" size="small">已开启直答</el-tag>
                </div>
                <dl>
                  <div><dt>问题</dt><dd>{{ item.question }}</dd></div>
                  <div><dt>答案</dt><dd>{{ item.answer }}</dd></div>
                </dl>
              </article>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="等级" width="92">
          <template #default="{ row }">
            <el-tag :type="severityTag(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类别" width="112">
          <template #default="{ row }">{{ categoryText(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="message" label="发现" min-width="360" />
        <el-table-column label="涉及知识" min-width="260">
          <template #default="{ row }">
            <div class="question-list">
              <span v-for="item in row.evidence" :key="item.chunkId">#{{ item.chunkId }} {{ item.question }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="mobile-list">
        <article v-for="row in filteredFindings" :key="row.key" class="mobile-finding">
          <div class="mobile-heading">
            <el-tag :type="severityTag(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag>
            <span>{{ categoryText(row.category) }}</span>
          </div>
          <p>{{ row.message }}</p>
          <details>
            <summary>查看 {{ row.evidence.length }} 条相关知识</summary>
            <div v-for="item in row.evidence" :key="item.chunkId" class="mobile-evidence">
              <strong>#{{ item.chunkId }} {{ item.question }}</strong>
              <span>{{ item.answer }}</span>
            </div>
          </details>
        </article>
      </div>

      <el-empty v-if="!loading && !filteredFindings.length" description="当前筛选条件下没有发现" />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import request from '../../api/index.js'

const loading = ref(false)
const errorText = ref('')
const report = ref(null)
const severity = ref('ALL')
const category = ref('')
const keyword = ref('')
const severityOptions = [
  { label: '全部', value: 'ALL' },
  { label: '阻断项', value: 'BLOCKER' },
  { label: '高风险', value: 'HIGH' },
  { label: '待复核', value: 'REVIEW' }
]

const findings = computed(() => (report.value?.findings || []).map((finding, index) => ({
  ...finding,
  key: `${finding.code}-${finding.evidence?.[0]?.chunkId || index}-${index}`
})))
const categories = computed(() => [...new Set(findings.value.map(item => item.category))].sort())
const filteredFindings = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return findings.value.filter(item => {
    if (severity.value !== 'ALL' && item.severity !== severity.value) return false
    if (category.value && item.category !== category.value) return false
    if (!query) return true
    const searchable = [item.message, item.code, ...item.evidence.flatMap(evidence => [
      String(evidence.chunkId), evidence.question, evidence.answer, evidence.documentTitle
    ])].filter(Boolean).join(' ').toLowerCase()
    return searchable.includes(query)
  })
})

async function loadAudit() {
  loading.value = true
  errorText.value = ''
  try {
    const response = await request.get('/admin/knowledge-quality/audit')
    report.value = response.data || null
  } catch (error) {
    report.value = null
    errorText.value = error?.message || '知识质量审计失败'
  } finally {
    loading.value = false
  }
}

function severityCount(value) {
  return report.value?.bySeverity?.[value] || 0
}

function severityText(value) {
  return { BLOCKER: '阻断', HIGH: '高风险', REVIEW: '待复核' }[value] || value
}

function severityTag(value) {
  return { BLOCKER: 'danger', HIGH: 'warning', REVIEW: 'info' }[value] || 'info'
}

function categoryText(value) {
  return {
    CONSISTENCY: '口径一致性', CONTENT_HYGIENE: '内容洁净度', PRICE: '价格',
    VALIDITY: '期限政策', LEGAL: '法律合规', MARKETING: '营销表述',
    DIRECT_ANSWER: '直答安全'
  }[value] || value
}

onMounted(loadAudit)
</script>

<style scoped>
.audit-page { min-height:100%; padding:20px; background:#fff; border:1px solid #e5e7eb; }
.page-header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:18px; }
.page-header h1 { margin:0; font-size:20px; line-height:1.4; letter-spacing:0; color:#1f2937; }
.page-header p { margin:4px 0 0; font-size:13px; color:#6b7280; }
.audit-content { min-height:240px; }
.summary-grid { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:12px; margin:18px 0; }
.summary-item { min-height:82px; padding:14px 16px; border:1px solid #e5e7eb; border-left-width:4px; }
.summary-item span { display:block; font-size:13px; color:#6b7280; }
.summary-item strong { display:block; margin-top:6px; font-size:26px; line-height:1; letter-spacing:0; color:#111827; }
.summary-item.blocker { border-left-color:#dc2626; }
.summary-item.high { border-left-color:#d97706; }
.summary-item.review { border-left-color:#64748b; }
.summary-item.total { border-left-color:#2563eb; }
.filters { display:grid; grid-template-columns:auto 180px minmax(240px, 1fr); gap:12px; align-items:center; margin-bottom:16px; }
.evidence-list { display:grid; gap:12px; padding:8px 16px 16px 56px; }
.evidence-item { padding:12px 14px; border-left:3px solid #cbd5e1; background:#f8fafc; }
.evidence-heading { display:flex; align-items:center; gap:10px; flex-wrap:wrap; color:#475569; }
.evidence-heading strong { color:#1f2937; }
.evidence-item dl { margin:10px 0 0; }
.evidence-item dl div { display:grid; grid-template-columns:48px 1fr; gap:10px; margin-top:7px; }
.evidence-item dt { color:#64748b; }
.evidence-item dd { margin:0; white-space:pre-wrap; line-height:1.6; color:#273244; }
.question-list { display:grid; gap:5px; line-height:1.45; }
.mobile-list { display:none; }
@media (max-width: 900px) {
  .audit-page { padding:14px; }
  .summary-grid { grid-template-columns:repeat(2, minmax(0, 1fr)); }
  .filters { grid-template-columns:1fr; }
  .desktop-table { display:none; }
  .mobile-list { display:grid; gap:10px; }
  .mobile-finding { padding:14px; border:1px solid #e5e7eb; }
  .mobile-heading { display:flex; align-items:center; gap:8px; color:#64748b; }
  .mobile-finding p { margin:10px 0; line-height:1.55; color:#273244; }
  .mobile-finding summary { cursor:pointer; color:#2563eb; }
  .mobile-evidence { display:grid; gap:5px; margin-top:10px; padding:10px; background:#f8fafc; }
  .mobile-evidence span { white-space:pre-wrap; line-height:1.5; color:#475569; }
}
@media (max-width: 520px) {
  .page-header { align-items:flex-start; }
  .summary-grid { grid-template-columns:1fr 1fr; gap:8px; }
  .summary-item { min-height:72px; padding:11px; }
  .summary-item strong { font-size:22px; }
}
</style>
