<template>
  <section class="migration-page">
    <header class="page-header"><div><h1>文档迁移</h1><p>迁移任务、审核状态和发布进度</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <el-table v-loading="loading" :data="jobs" border stripe row-key="id">
      <el-table-column prop="id" label="任务" width="80" />
      <el-table-column label="文档版本" min-width="210"><template #default="{row}">源 #{{ row.sourceDocumentId }} (v{{ row.sourceVersionId || '-' }}) → 目标 #{{ row.targetDocumentId || '-' }} (v{{ row.targetVersionId || '-' }})</template></el-table-column>
      <el-table-column prop="knowledgeSetKey" label="知识集" min-width="150" />
      <el-table-column label="状态" width="140"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="进度" width="150"><template #default="{row}"><el-progress :percentage="progress(row)" :status="row.status === 'FAILED' ? 'exception' : undefined" /></template></el-table-column>
      <el-table-column label="阻断/冲突" width="110"> <template #default="{row}">{{ row.blockingConflicts || 0 }} / {{ row.conflictUnits || 0 }}</template></el-table-column>
      <el-table-column prop="lastError" label="错误" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="150"><template #default="{row}"><el-button link type="primary" @click="$router.push(`/knowledge/migrations/${row.id}`)">详情</el-button><el-button v-if="row.status === 'FAILED'" link type="warning" @click="retry(row)">重试</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !jobs.length" description="暂无迁移任务" />
  </section>
</template>
<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/index.js'
const jobs = ref([]); const loading = ref(false); const error = ref(''); let timer
const active = new Set(['PENDING','EXTRACTING','EMBEDDING','CONFLICT_CHECKING','REVIEW_REQUIRED','READY_TO_SWITCH','RUNNING'])
const load = async () => { loading.value = true; error.value = ''; try { const r = await request.get('/admin/knowledge/migrations'); jobs.value = Array.isArray(r.data) ? r.data : [] } catch (e) { error.value = e?.message || '迁移任务加载失败' } finally { loading.value = false } }
const progress = row => row.totalUnits ? Math.min(100, Math.round((row.processedUnits || 0) * 100 / row.totalUnits)) : (row.status === 'COMPLETED' ? 100 : 0)
const statusType = status => ({ COMPLETED:'success', FAILED:'danger', STALE:'info', READY_TO_SWITCH:'warning', REVIEW_REQUIRED:'warning' }[status] || 'primary')
const retry = async row => { try { await ElMessageBox.confirm('确认重新执行该迁移任务？','重试确认'); await request.post(`/admin/knowledge/migrations/${row.id}/retry`); ElMessage.success('已重新排队'); await load() } catch (e) { if (e !== 'cancel' && e !== 'close') error.value = e?.message || '重试失败' } }
onMounted(async () => { await load(); timer = window.setInterval(() => { if (jobs.value.some(row => active.has(row.status))) load() }, 5000) })
onUnmounted(() => window.clearInterval(timer))
</script>
<style scoped>
.migration-page { min-width: 0; }.page-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:14px; }.page-header h1 { margin:0 0 6px; font-size:20px; }.page-header p { margin:0; color:#909399; }.el-alert { margin-bottom:12px; }
</style>
