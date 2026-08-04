<template>
  <div class="unit-details">
    <section class="detail-section evidence-section">
      <h3>原文证据</h3>
      <div v-if="sourceSpans.values.length" class="source-spans">
        <div
          v-for="(span, index) in sourceSpans.values"
          :key="`${span.chunkId}-${span.start}-${index}`"
          class="source-span"
        >
          <div class="source-span-header">
            <el-tag size="small" effect="plain">切片 #{{ span.chunkId ?? '未知' }}</el-tag>
            <span v-if="hasOffsets(span)" class="muted">
              字符 {{ span.start }}-{{ span.end }}
            </span>
          </div>
          <blockquote>{{ span.quote || '未提供原文引用' }}</blockquote>
        </div>
      </div>
      <span v-else :class="sourceSpans.error ? 'parse-error' : 'muted'">
        {{ sourceSpans.error ? '原文证据格式异常，无法解析' : '无原文证据' }}
      </span>
    </section>

    <section class="detail-section">
      <h3>语义信息</h3>
      <dl class="field-list">
        <div>
          <dt>意图</dt>
          <dd>{{ unit.intent || '无' }}</dd>
        </div>
        <div>
          <dt>实体</dt>
          <dd><ValueList :state="entities" /></dd>
        </div>
        <div>
          <dt>适用条件</dt>
          <dd><ValueList :state="conditions" /></dd>
        </div>
        <div>
          <dt>排除条件</dt>
          <dd><ValueList :state="exclusions" /></dd>
        </div>
        <div>
          <dt>查询扩展</dt>
          <dd><ValueList :state="queryVariants" ordered /></dd>
        </div>
      </dl>
    </section>

    <section class="detail-section">
      <h3>模型候选标注</h3>
      <span v-if="metadata.error" class="parse-error">元数据格式异常，无法解析</span>
      <dl v-else class="metadata-grid">
        <div><dt>产品</dt><dd>{{ metadataValue('product') }}</dd></div>
        <div><dt>渠道</dt><dd>{{ metadataValue('channel') }}</dd></div>
        <div><dt>受众</dt><dd>{{ metadataValue('audience') }}</dd></div>
        <div><dt>风险等级</dt><dd>{{ riskLevelText }}</dd></div>
        <div><dt>生效时间</dt><dd>{{ metadataValue('effectiveFrom', 'effective_from') }}</dd></div>
        <div><dt>失效时间</dt><dd>{{ metadataValue('effectiveTo', 'effective_to') }}</dd></div>
      </dl>
    </section>

    <section class="detail-section">
      <h3>抽取信息</h3>
      <dl class="metadata-grid">
        <div><dt>抽取模型</dt><dd>{{ unit.extractorModel || '未知' }}</dd></div>
        <div><dt>提示词版本</dt><dd>{{ unit.promptVersion || '未知' }}</dd></div>
        <div><dt>Schema 版本</dt><dd>{{ unit.schemaVersion || '未知' }}</dd></div>
        <div><dt>单元键</dt><dd class="technical-value">{{ unit.unitKey || '未知' }}</dd></div>
        <div class="wide-field"><dt>来源哈希</dt><dd class="technical-value">{{ unit.sourceHash || '未知' }}</dd></div>
      </dl>
    </section>

    <section v-if="unit.status !== 'DRAFT'" class="detail-section">
      <h3>审核记录</h3>
      <dl class="metadata-grid">
        <div><dt>审核人 ID</dt><dd>{{ unit.reviewedBy ?? '未知' }}</dd></div>
        <div><dt>审核时间</dt><dd>{{ reviewTime }}</dd></div>
        <div class="wide-field"><dt>审核备注</dt><dd>{{ unit.reviewReason || '无' }}</dd></div>
      </dl>
    </section>
  </div>
</template>

<script setup>
import { computed, defineComponent, h } from 'vue'

const props = defineProps({
  unit: { type: Object, required: true }
})

const ValueList = defineComponent({
  props: {
    state: { type: Object, required: true },
    ordered: { type: Boolean, default: false }
  },
  setup(componentProps) {
    return () => {
      if (componentProps.state.error) {
        return h('span', { class: 'parse-error' }, '字段格式异常，无法解析')
      }
      if (!componentProps.state.values.length) {
        return h('span', { class: 'muted' }, '无')
      }
      const tag = componentProps.ordered ? 'ol' : 'ul'
      return h(tag, { class: 'value-list' }, componentProps.state.values.map((value, index) =>
        h('li', { key: `${index}-${String(value)}` }, displayValue(value))
      ))
    }
  }
})

const entities = computed(() => parseArray(props.unit.entitiesJson))
const conditions = computed(() => parseArray(props.unit.conditionsJson))
const exclusions = computed(() => parseArray(props.unit.exclusionsJson))
const queryVariants = computed(() => parseArray(props.unit.queryVariantsJson))
const sourceSpans = computed(() => parseArray(props.unit.sourceSpansJson, true))
const metadata = computed(() => parseObject(props.unit.metadataJson))

const riskLevelText = computed(() => ({
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  UNKNOWN: '未知'
}[metadataValue('riskLevel', 'risk_level')] || metadataValue('riskLevel', 'risk_level')))

const reviewTime = computed(() => {
  if (!props.unit.reviewedAt) return '未知'
  const date = new Date(props.unit.reviewedAt)
  return Number.isNaN(date.getTime()) ? String(props.unit.reviewedAt) : date.toLocaleString('zh-CN')
})

function parseValue(raw) {
  if (raw === null || raw === undefined || raw === '') return { value: null, error: false }
  if (typeof raw === 'object') return { value: raw, error: false }
  try {
    return { value: JSON.parse(raw), error: false }
  } catch {
    return { value: null, error: true }
  }
}

function parseArray(raw, objectsOnly = false) {
  const parsed = parseValue(raw)
  if (parsed.error) return { values: [], error: true }
  if (parsed.value === null) return { values: [], error: false }
  if (!Array.isArray(parsed.value)) return { values: [], error: true }
  const values = objectsOnly
    ? parsed.value.filter(value => value && typeof value === 'object' && !Array.isArray(value))
    : parsed.value.filter(value => value !== null && value !== '')
  return { values, error: values.length !== parsed.value.length }
}

function parseObject(raw) {
  const parsed = parseValue(raw)
  if (parsed.error) return { value: {}, error: true }
  if (parsed.value === null) return { value: {}, error: false }
  if (typeof parsed.value !== 'object' || Array.isArray(parsed.value)) {
    return { value: {}, error: true }
  }
  return { value: parsed.value, error: false }
}

function metadataValue(...keys) {
  for (const key of keys) {
    const value = metadata.value.value[key]
    if (value !== null && value !== undefined && String(value).trim()) return String(value)
  }
  return '无'
}

function hasOffsets(span) {
  return Number.isInteger(span?.start) && Number.isInteger(span?.end)
}

function displayValue(value) {
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}
</script>

<style scoped>
.unit-details { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); gap:22px 28px; padding:10px 18px 18px; }
.detail-section { min-width:0; }
.evidence-section { grid-column:1 / -1; }
.detail-section h3 { margin:0 0 10px; color:#303133; font-size:14px; font-weight:600; }
.source-spans { display:grid; gap:10px; }
.source-span { padding:10px 12px; border-left:3px solid #409eff; background:#f5f7fa; }
.source-span-header { display:flex; align-items:center; gap:8px; margin-bottom:7px; }
.source-span blockquote { margin:0; color:#303133; line-height:1.65; white-space:pre-wrap; overflow-wrap:anywhere; }
.field-list,
.metadata-grid { margin:0; }
.field-list > div { display:grid; grid-template-columns:84px minmax(0, 1fr); gap:10px; padding:7px 0; border-bottom:1px solid #ebeef5; }
.field-list > div:last-child { border-bottom:0; }
.field-list dt,
.metadata-grid dt { color:#909399; }
.field-list dd,
.metadata-grid dd { min-width:0; margin:0; line-height:1.55; overflow-wrap:anywhere; }
.metadata-grid { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); gap:12px 20px; }
.metadata-grid > div { min-width:0; }
.metadata-grid dt { margin-bottom:3px; font-size:12px; }
.wide-field { grid-column:1 / -1; }
.technical-value { font-family:Consolas, "Courier New", monospace; font-size:12px; }
.value-list { margin:0; padding-left:18px; }
.value-list li { margin-bottom:4px; overflow-wrap:anywhere; }
.value-list li:last-child { margin-bottom:0; }
.muted { color:#909399; }
.parse-error { color:#d73737; }

@media (max-width: 760px) {
  .unit-details { display:block; padding:4px 0 10px; }
  .detail-section { padding:14px 0; border-bottom:1px solid #ebeef5; }
  .detail-section:last-child { border-bottom:0; }
  .field-list > div { grid-template-columns:76px minmax(0, 1fr); }
  .metadata-grid { gap:12px; }
  .source-span { padding:10px; }
}
</style>
