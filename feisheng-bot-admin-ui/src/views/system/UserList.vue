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
      <el-table-column prop="username" label="系统用户">
        <template #default="{row}">{{ systemUserText(row) }}</template>
      </el-table-column>
      <el-table-column prop="realName" label="姓名">
        <template #default="{row}">{{ systemUserText(row) }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱状态">
        <template #default="{row}">{{ row.email ? '已配置' : '未配置' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{row}"><el-tag :type="row.status===1?'success':'danger'">{{row.status===1?'启用':'禁用'}}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{row}"><el-button size="small" @click="edit(row)">编辑</el-button><el-button size="small" type="danger" @click="del(row.id)">删除</el-button></template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:20px" layout="prev,pager,next" :total="total" :current-page="page" @current-change="onPageChange" />
  </el-card>
  <el-dialog v-model="dialogVisible" :title="isEdit?'编辑用户':'新增用户'">
    <el-form :model="form" label-width="100px">
      <el-form-item v-if="!isEdit" label="登录账号"><el-input v-model="form.username" placeholder="请输入登录账号" /></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" /></el-form-item>
      <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      <el-form-item label="角色"><el-select v-model="form.roleIds" multiple style="width:100%"><el-option v-for="r in allRoles" :key="r.id" :label="localizedSystemText(r.roleName, '系统角色')" :value="r.id" /></el-select></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
  </el-dialog>
</template>
<script setup>
import { ref, onMounted, reactive } from 'vue'
import request from '../../api/index.js'
import { ElMessage } from 'element-plus'
import { localizedSystemText } from '../../utils/displayText.js'
const users = ref([]); const total = ref(0); const page = ref(1); const dialogVisible = ref(false); const isEdit = ref(false)
const form = reactive({id:null,username:'',realName:'',email:'',password:'',status:1,roleIds:[]})
const allRoles = ref([])
const systemUserText = row => row.realName && !/[A-Za-z]/.test(row.realName) ? row.realName : `系统用户 ${row.id}`
async function fetchRoles() { try { const r=await request.get('/admin/role/all'); allRoles.value=r.data } catch(e) { allRoles.value=[] } }
const fetch = async () => { const r=await request.get('/admin/user/list',{params:{page:page.value,size:20}}); users.value=r.data.records; total.value=r.data.total }
const onPageChange = (val) => { page.value = val; fetch() }
const openAdd = () => { Object.assign(form,{id:null,username:'',realName:'',email:'',password:'',status:1,roleIds:[]}); isEdit.value=false; dialogVisible.value=true }
const edit = async (row) => { Object.assign(form,row,{password:'',roleIds:[]}); try { const r=await request.get('/admin/user/'+row.id+'/roles'); form.roleIds=r.data||[] } catch(e){} isEdit.value=true; dialogVisible.value=true }
const del = async (id) => { await request.delete('/admin/user/'+id); ElMessage.success('已删除'); fetch() }
const save = async () => {
  let userId
  if(isEdit.value) { await request.put('/admin/user/update',form); userId=form.id }
  else { const r=await request.post('/admin/user/add',form); userId=r.data }
  await request.put('/admin/user/'+userId+'/roles', form.roleIds || [])
  ElMessage.success('已保存'); dialogVisible.value=false; fetch()
}
onMounted(() => { fetch(); fetchRoles() })
</script>
