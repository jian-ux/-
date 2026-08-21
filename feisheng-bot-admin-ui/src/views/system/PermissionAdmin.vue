<template>
  <el-card class="permission-page">
    <template #header>
      <div class="page-header">
        <span>权限分配</span>
        <el-button
          type="primary"
          :icon="Check"
          :loading="saving"
          :disabled="!selectedUserId"
          @click="save"
        >保存权限</el-button>
      </div>
    </template>

    <el-form label-width="80px" class="user-selector">
      <el-form-item label="系统用户">
        <el-select
          v-model="selectedUserId"
          filterable
          placeholder="请选择用户"
          @change="loadUserPermissions"
        >
          <el-option
            v-for="user in users"
            :key="user.id"
            :value="user.id"
            :label="userOptionLabel(user)"
          >
            <span>{{ user.realName || user.username }}</span>
            <span class="account-text">{{ user.username }}</span>
          </el-option>
        </el-select>
        <el-tag v-if="selectedUser" :type="selectedUser.status === 1 ? 'success' : 'danger'">
          {{ selectedUser.status === 1 ? '启用' : '禁用' }}
        </el-tag>
      </el-form-item>
    </el-form>

    <div v-loading="loading" class="permission-tree-wrap">
      <el-tree
        ref="permissionTree"
        :data="tree"
        :props="{ children: 'children', label: 'label', disabled: () => !selectedUserId }"
        node-key="id"
        show-checkbox
        default-expand-all
        :empty-text="selectedUserId ? '暂无可分配模块' : '请先选择用户'"
      />
    </div>
  </el-card>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '../../api/index.js'

const route = useRoute()
const users = ref([])
const tree = ref([])
const selectedUserId = ref(null)
const permissionTree = ref(null)
const loading = ref(false)
const saving = ref(false)
const selectedUser = computed(() => users.value.find(user => user.id === selectedUserId.value))

function userOptionLabel(user) {
  const name = String(user.realName || '').trim()
  return name ? `${name}（${user.username}）` : user.username
}

async function loadUserPermissions() {
  permissionTree.value?.setCheckedKeys([])
  if (!selectedUserId.value) return
  const userId = selectedUserId.value
  loading.value = true
  try {
    const response = await request.get(`/admin/permission/user/${userId}`)
    if (selectedUserId.value !== userId) return
    await nextTick()
    permissionTree.value?.setCheckedKeys(response.data || [])
  } finally {
    if (selectedUserId.value === userId) loading.value = false
  }
}

async function save() {
  if (!selectedUserId.value) return
  saving.value = true
  try {
    const permissionIds = [
      ...(permissionTree.value?.getCheckedKeys(false) || []),
      ...(permissionTree.value?.getHalfCheckedKeys() || [])
    ]
    await request.put(`/admin/permission/user/${selectedUserId.value}`, [...new Set(permissionIds)])
    ElMessage.success('用户权限已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const [treeResponse, userResponse] = await Promise.all([
    request.get('/admin/permission/tree'),
    request.get('/admin/permission/users')
  ])
  tree.value = treeResponse.data || []
  users.value = userResponse.data || []
  const requestedUserId = Number(route.query.userId)
  const requestedUser = users.value.find(user => user.id === requestedUserId)
  selectedUserId.value = requestedUser?.id || users.value[0]?.id || null
  await loadUserPermissions()
})
</script>

<style scoped>
.page-header { display:flex; align-items:center; justify-content:space-between; gap:12px; font-weight:600; }
.user-selector { max-width:620px; }
.user-selector :deep(.el-form-item__content) { display:flex; flex-wrap:wrap; gap:10px; }
.user-selector .el-select { width:min(420px, 100%); }
.account-text { float:right; margin-left:24px; color:#909399; }
.permission-tree-wrap { min-height:280px; padding:16px 20px; border:1px solid #dcdfe6; border-radius:6px; }
.permission-tree-wrap :deep(.el-tree-node__content) { min-height:36px; height:auto; }
@media (max-width: 560px) {
  .permission-page :deep(.el-card__header),
  .permission-page :deep(.el-card__body) { padding:14px 12px; }
  .page-header { align-items:stretch; flex-direction:column; }
  .page-header .el-button { width:100%; margin:0; }
  .user-selector { margin-top:4px; }
  .user-selector :deep(.el-form-item) { display:block; }
  .user-selector :deep(.el-form-item__label) { display:block; width:auto !important; text-align:left; }
  .permission-tree-wrap { padding:10px 6px; overflow-x:auto; }
}
</style>
