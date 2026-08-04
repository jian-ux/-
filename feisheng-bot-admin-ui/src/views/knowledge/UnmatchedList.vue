<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>未命中问题收集</span>
        <el-tag type="info">智能试聊中知识库无法回答的问题会自动记录</el-tag>
      </div>
    </template>
    <el-table :data="questions" border stripe>
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
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../api/index.js'
import { ElMessage } from 'element-plus'
import { formatDateTime } from '../../utils/displayText.js'
const questions = ref([])

async function fetch() {
  try { const r = await request.get('/admin/unmatched/list'); questions.value = r.data?.records||[] }
  catch(e) { questions.value = [] }
}

async function resolve(id) {
  await request.put('/admin/unmatched/' + id + '/resolve')
  ElMessage.success('已标记'); fetch()
}

onMounted(fetch)
</script>
