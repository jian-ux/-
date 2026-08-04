<template>
  <el-row :gutter="20">
    <el-col :span="6" v-for="item in cards" :key="item.label">
      <el-card><div class="stat-value">{{ item.value }}</div><div class="stat-label">{{ item.label }}</div></el-card>
    </el-col>
  </el-row>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../api/index.js'
const cards = ref([
  {label:'活跃会话',value:'-'},
  {label:'总会话数',value:'-'},
  {label:'待处理工单',value:'-'},
  {label:'常见问题命中',value:'-'}
])
onMounted(async () => {
  try {
    const res = await request.get('/admin/statistics/overview')
    cards.value = [
      {label:'活跃会话',value:res.data.activeCount},
      {label:'总会话数',value:res.data.conversationCount},
      {label:'待处理工单',value:res.data.pendingTickets},
      {label:'常见问题命中',value:'暂无数据'}
    ]
  } catch(e) {}
})
</script>
<style scoped>
.stat-value { font-size:32px; font-weight:bold; color:#409eff; }
.stat-label { font-size:14px; color:#666; margin-top:8px; }
</style>
