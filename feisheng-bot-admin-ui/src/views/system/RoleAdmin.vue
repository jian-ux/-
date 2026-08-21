<template>
  <el-card>
    <template #header><div style="display:flex;justify-content:space-between;align-items:center"><span>角色管理</span><el-button type="primary" @click="openAdd">新增角色</el-button></div></template>
    <el-table v-loading="loading" :data="roles" border stripe>
      <el-table-column prop="roleName" label="角色名称">
        <template #default="{row}">{{ localizedSystemText(row.roleName, '系统角色') }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-tag :type="row.status===1?'success':'danger'">{{ row.status===1?'启用':'禁用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{row}"><el-button size="small" @click="edit(row)">编辑</el-button><el-button v-if="row.roleKey !== 'admin'" size="small" type="danger" @click="del(row.id)">删除</el-button></template>
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
    <el-dialog v-model="dialogVisible" :title="isEdit?'编辑角色':'新增角色'" width="450px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="角色名称"><el-input v-model="form.roleName" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, onMounted, reactive } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { localizedSystemText } from '../../utils/displayText.js'
const roles = ref([]); const dialogVisible = ref(false); const isEdit = ref(false)
const loading = ref(false); const total = ref(0); const page = ref(1); const pageSize = 10
const form = reactive({ roleName:'', roleKey:'' })
async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/role/list', { params: { page: page.value, size: pageSize } })
    roles.value = r.data?.records || []
    total.value = r.data?.total || 0
    if (!roles.value.length && total.value > 0 && page.value > 1) {
      page.value = Math.max(1, Math.ceil(total.value / pageSize))
      return await fetch()
    }
  } catch {
    roles.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
function openAdd() { Object.assign(form,{roleName:'',roleKey:'role_' + Date.now()}); isEdit.value=false; dialogVisible.value=true }
function edit(row) { Object.assign(form,row); isEdit.value=true; dialogVisible.value=true }
async function save() { await request.post('/admin/role/save', form); ElMessage.success('已保存'); dialogVisible.value=false; fetch() }
async function del(id) { await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await request.delete('/admin/role/'+id); ElMessage.success('已删除'); fetch() }
onMounted(fetch)
</script>
<style scoped>
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
</style>
