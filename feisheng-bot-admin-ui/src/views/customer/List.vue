<template>
  <el-card>
    <template #header><span>客户管理</span></template>
    <el-input v-model="keyword" placeholder="搜索客户姓名/昵称/手机..." style="width:300px;margin-bottom:15px" clearable @clear="fetch" @keyup.enter="fetch" />
    <el-table :data="customers" border stripe @row-click="goDetail">
      <el-table-column prop="id" label="编号" width="68" />
      <el-table-column prop="nickname" label="昵称">
        <template #default="{row}">{{ localizedSystemText(row.nickname, '客户昵称') }}</template>
      </el-table-column>
      <el-table-column prop="name" label="姓名">
        <template #default="{row}">{{ localizedSystemText(row.name, '客户姓名') }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机" width="120" />
      <el-table-column prop="channelType" label="渠道" width="100">
        <template #default="{row}">{{ channelTypeText(row.channelType) }}</template>
      </el-table-column>
      <el-table-column prop="totalConversations" label="会话数" width="80" />
      <el-table-column prop="lastContactTime" label="最后联系" width="180">
        <template #default="{row}">{{ formatDateTime(row.lastContactTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{row}"><el-button size="small" @click.stop="goDetail(row)">详情</el-button></template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:20px" layout="prev,pager,next" :total="total" :current-page="page" @current-change="onPageChange" />
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '../../api/index.js'
import { channelTypeText, formatDateTime, localizedSystemText } from '../../utils/displayText.js'
const router = useRouter()
const customers = ref([]); const total = ref(0); const page = ref(1); const keyword = ref('')
const fetch = async () => {
  const r = await request.get('/admin/customer/list', {params:{page:page.value,size:20,keyword:keyword.value}})
  customers.value = r.data.records; total.value = r.data.total
}
const onPageChange = (val) => { page.value = val; fetch() }
const goDetail = (row) => router.push('/customer/'+row.id)
onMounted(fetch)
</script>
