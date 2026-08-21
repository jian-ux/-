<template>
  <el-card>
    <template #header><span>操作日志</span></template>
    <el-table v-loading="loading" :data="logs" border stripe>
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column label="用户" width="120"><template #default>系统管理员</template></el-table-column>
      <el-table-column prop="action" label="操作"><template #default="{row}">{{ operationText(row.action) }}</template></el-table-column>
      <el-table-column prop="target" label="目标"><template #default="{row}">{{ targetText(row.target) }}</template></el-table-column>
      <el-table-column prop="ip" label="网络地址" width="140" />
      <el-table-column prop="result" label="结果" width="200" show-overflow-tooltip><template #default="{row}">{{ resultText(row.result) }}</template></el-table-column>
      <el-table-column prop="createTime" label="时间" width="180"><template #default="{row}">{{ formatDateTime(row.createTime) }}</template></el-table-column>
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
import request from '../../api/index.js'
import { formatDateTime } from '../../utils/displayText.js'
const logs = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = 10
const operationText = value => ({ CREATE:'新增', UPDATE:'更新', DELETE:'删除', LOGIN:'登录', LOGOUT:'退出登录' }[value] || '系统操作')
const targetText = value => /[A-Za-z]/.test(value || '') ? '系统资源' : (value || '-')
const resultText = value => /[A-Za-z]/.test(value || '') ? '操作完成' : (value || '操作完成')
async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/log/operation', { params: { page: page.value, size: pageSize } })
    logs.value = r.data?.records || []
    total.value = r.data?.total || 0
  } catch {
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
onMounted(fetch)
</script>
<style scoped>
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
</style>
