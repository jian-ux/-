<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>用户管理</span>
        <el-button type="primary" @click="openAdd">新增用户</el-button>
      </div>
    </template>
    <el-table :data="users" border stripe>
      <el-table-column prop="id" label="编号" width="80" />
      <el-table-column prop="username" label="登录账号" />
      <el-table-column prop="realName" label="姓名">
        <template #default="{row}">{{ row.realName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱状态">
        <template #default="{row}">{{ row.email ? '已配置' : '未配置' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{row}"><el-tag :type="row.status===1?'success':'danger'">{{row.status===1?'启用':'禁用'}}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{row}">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button v-if="!row.admin" size="small" :icon="Key" @click="openPermissions(row)">权限</el-button>
          <el-button v-if="!row.admin" size="small" type="danger" @click="del(row.id)">删除</el-button>
          <el-tag v-else type="info">超级管理员</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:20px" layout="total,prev,pager,next" :total="total" :page-size="pageSize" :current-page="page" @current-change="onPageChange" />
  </el-card>
  <el-dialog v-model="dialogVisible" :title="isEdit?'编辑用户':'新增用户'">
    <el-form :model="form" label-width="100px">
      <el-form-item v-if="!isEdit" label="登录账号"><el-input v-model="form.username" placeholder="请输入登录账号" /></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" /></el-form-item>
      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>
<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import request from '../../api/index.js'
import { Key } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
const router = useRouter()
const users = ref([]); const total = ref(0); const page = ref(1); const dialogVisible = ref(false); const isEdit = ref(false)
const saving = ref(false)
const pageSize = 10
const form = reactive({id:null,username:'',realName:'',email:'',password:'',status:1})
const fetch = async () => { const r=await request.get('/admin/user/list',{params:{page:page.value,size:pageSize}}); users.value=r.data.records; total.value=r.data.total }
const onPageChange = (val) => { page.value = val; fetch() }
const openAdd = () => { Object.assign(form,{id:null,username:'',realName:'',email:'',password:'',status:1}); isEdit.value=false; dialogVisible.value=true }
const edit = row => { Object.assign(form,row,{password:''}); isEdit.value=true; dialogVisible.value=true }
const openPermissions = row => router.push({ path:'/system/permission', query:{ userId:row.id } })
const del = async (id) => {
  await ElMessageBox.confirm('确认删除该用户？','提示',{type:'warning'})
  await request.delete('/admin/user/'+id)
  ElMessage.success('已删除')
  fetch()
}
const save = async () => {
  saving.value = true
  try {
    if(isEdit.value) {
      await request.put('/admin/user/update',form)
      ElMessage.success('用户已保存')
      dialogVisible.value=false
      fetch()
    } else {
      const response=await request.post('/admin/user/add',form)
      ElMessage.success('用户已创建，请分配可见模块')
      dialogVisible.value=false
      router.push({ path:'/system/permission', query:{ userId:response.data } })
    }
  } finally {
    saving.value = false
  }
}
onMounted(fetch)
</script>
