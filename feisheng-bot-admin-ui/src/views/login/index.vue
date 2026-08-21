<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2 style="text-align:center;margin-bottom:20px">飞晟智能客服</h2>
      <el-form :model="form">
        <el-form-item><el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" show-password /></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" style="width:100%" @click="handleLogin">登录</el-button></el-form-item>
      </el-form>
    </el-card>
  </div>
</template>
<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const handleLogin = async () => {
  loading.value = true
  try { await auth.login(form.username, form.password); ElMessage.success('登录成功'); router.push('/') }
  catch {}
  finally { loading.value = false }
}
</script>
<style scoped>
.login-container { height: 100vh; display: flex; align-items: center; justify-content: center; background: #f0f2f5; }
.login-card { width: 400px; }
</style>
