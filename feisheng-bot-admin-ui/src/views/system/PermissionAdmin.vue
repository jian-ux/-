<template>
  <el-card>
    <template #header><div style="display:flex;justify-content:space-between;align-items:center"><span>权限管理</span><el-button type="primary" @click="openAdd(0)">新增根权限</el-button></div></template>
    <el-tree :data="tree" :props="{children:'children',label:'label'}" node-key="id" default-expand-all>
      <template #default="{node,data}">
        <div style="display:flex;align-items:center;gap:10px;padding:4px 0">
          <span>{{ localizedSystemText(data.label, '系统权限') }}</span>
          <div style="margin-left:auto;display:flex;gap:4px">
            <el-button size="small" @click.stop="openAdd(data.id)">新增子项</el-button>
            <el-button size="small" @click.stop="edit(data)">编辑</el-button>
            <el-button size="small" type="danger" @click.stop="del(data.id)">删除</el-button>
          </div>
        </div>
      </template>
    </el-tree>
    <el-dialog v-model="dialogVisible" :title="isEdit?'编辑权限':'新增权限'" width="450px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="权限名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" /></el-form-item>
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
const tree = ref([]); const dialogVisible = ref(false); const isEdit = ref(false); const parentId = ref(0)
const form = reactive({ id:null, name:'', permission:'', path:'', sort:0 })
async function fetch() { try { const r=await request.get('/admin/permission/tree'); tree.value=r.data } catch(e){ tree.value=[] } }
function openAdd(pid) { parentId.value = pid; Object.assign(form,{id:null,name:'',permission:'permission:' + Date.now(),path:'',sort:0}); isEdit.value=false; dialogVisible.value=true }
function edit(data) { Object.assign(form,{id:data.id,name:data.label,permission:data.permission,path:data.path,sort:data.sort||0}); isEdit.value=true; dialogVisible.value=true }
async function save() { const payload={...form, parentId: isEdit.value ? (form.parentId||0) : parentId.value}; await request.post('/admin/permission/save', payload); ElMessage.success('已保存'); dialogVisible.value=false; fetch() }
async function del(id) { await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await request.delete('/admin/permission/'+id); ElMessage.success('已删除'); fetch() }
onMounted(fetch)
</script>
