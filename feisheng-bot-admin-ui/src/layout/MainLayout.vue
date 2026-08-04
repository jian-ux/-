<template>
  <el-container class="app-shell">
    <button
      v-if="isMobile && sidebarOpen"
      class="sidebar-overlay"
      type="button"
      aria-label="关闭导航"
      @click="sidebarOpen = false"
    />
    <el-aside
      width="220px"
      :class="['app-sidebar', { 'is-open': sidebarOpen }]"
      :aria-hidden="isMobile && !sidebarOpen"
      :inert="isMobile && !sidebarOpen"
    >
      <div class="app-brand">飞晟智能客服</div>
      <el-menu :default-active="route.path" router background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff">
        <el-menu-item index="/dashboard"><el-icon><Odometer /></el-icon><span>总览</span></el-menu-item>
        <el-menu-item index="/channel"><el-icon><Connection /></el-icon><span>渠道配置</span></el-menu-item>
        <el-menu-item index="/conversation"><el-icon><ChatDotSquare /></el-icon><span>对话监控</span></el-menu-item>
        <el-menu-item index="/playground"><el-icon><ChatLineSquare /></el-icon><span>智能试聊</span></el-menu-item>
        <el-menu-item index="/customer"><el-icon><UserFilled /></el-icon><span>客户管理</span></el-menu-item>
        <el-menu-item index="/intent"><el-icon><SetUp /></el-icon><span>意图管理</span></el-menu-item>
        <el-menu-item index="/ai/model"><el-icon><Cpu /></el-icon><span>智能模型</span></el-menu-item>
        <el-menu-item index="/ticket"><el-icon><Ticket /></el-icon><span>工单管理</span></el-menu-item>
        <el-menu-item index="/log"><el-icon><Document /></el-icon><span>操作日志</span></el-menu-item>
        <el-sub-menu index="/knowledge">
          <template #title><el-icon><Notebook /></el-icon><span>知识库</span></template>
          <el-menu-item index="/knowledge/faq"><span>常见问题管理</span></el-menu-item>
          <el-menu-item index="/knowledge/upload"><span>知识库上传</span></el-menu-item>
          <el-menu-item index="/knowledge/semantic-units"><span>结构化知识审核</span></el-menu-item>
          <el-menu-item index="/knowledge/unmatched"><span>未命中问题</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="/system">
          <template #title><el-icon><User /></el-icon><span>系统管理</span></template>
          <el-menu-item index="/system/user"><span>用户管理</span></el-menu-item>
          <el-menu-item index="/system/role"><span>角色管理</span></el-menu-item>
          <el-menu-item index="/system/permission"><span>权限管理</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="/settings">
          <template #title><el-icon><Tools /></el-icon><span>设置</span></template>
          <el-menu-item index="/settings/rules"><span>安全规则</span></el-menu-item>
          <el-menu-item index="/settings/reply-strategy"><span>回复策略</span></el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container class="app-content" :inert="isMobile && sidebarOpen">
      <el-header class="app-header">
        <el-button
          v-if="isMobile"
          :icon="MenuIcon"
          circle
          text
          title="打开导航"
          aria-label="打开导航"
          @click="sidebarOpen = true"
        />
        <el-dropdown @command="handleCommand">
          <span style="cursor:pointer">{{ operatorText(auth.userInfo) }} <el-icon><ArrowDown /></el-icon></span>
          <template #dropdown><el-dropdown-item command="logout">退出登录</el-dropdown-item></template>
        </el-dropdown>
      </el-header>
      <el-main class="app-main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>
<script setup>
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Odometer, User, Connection, Notebook, ChatDotSquare, UserFilled, Cpu, Ticket, Document, ArrowDown, ChatLineSquare, Tools, SetUp, Menu as MenuIcon } from '@element-plus/icons-vue'
import { operatorText } from '../utils/displayText.js'
const route = useRoute(); const router = useRouter(); const auth = useAuthStore()
const isMobile = ref(false)
const sidebarOpen = ref(false)
let mobileMedia = null
const syncMobile = event => {
  isMobile.value = event.matches
  if (!event.matches) sidebarOpen.value = false
}
const handleCommand = (cmd) => { if (cmd === 'logout') { auth.logout(); router.push('/login') } }
watch(() => route.fullPath, () => { sidebarOpen.value = false })
onMounted(() => {
  mobileMedia = window.matchMedia('(max-width: 768px)')
  syncMobile(mobileMedia)
  mobileMedia.addEventListener('change', syncMobile)
  if (auth.token && !auth.userInfo) auth.fetchUserInfo()
})
onBeforeUnmount(() => mobileMedia?.removeEventListener('change', syncMobile))
</script>

<style scoped>
.app-shell { width:100%; height:100vh; height:100dvh; }
.app-sidebar { position:relative; z-index:20; flex:0 0 220px; overflow-y:auto; background:#304156; }
.app-brand { display:flex; align-items:center; justify-content:center; height:60px; color:#fff; font-size:18px; font-weight:700; border-bottom:1px solid rgba(255,255,255,0.1); }
.app-content { min-width:0; }
.app-header { display:flex; align-items:center; justify-content:flex-end; gap:12px; flex:0 0 60px; height:60px; padding:0 20px; background:#fff; border-bottom:1px solid #e6e6e6; }
.app-main { min-width:0; overflow:auto; padding:20px; background:#f0f2f5; }
.sidebar-overlay { display:none; }
@media (max-width: 768px) {
  .app-sidebar { position:fixed; inset:0 auto 0 0; width:220px !important; transform:translateX(-100%); transition:transform 180ms ease; box-shadow:4px 0 18px rgba(0,0,0,0.2); }
  .app-sidebar.is-open { transform:translateX(0); }
  .sidebar-overlay { display:block; position:fixed; z-index:19; inset:0; width:100%; height:100%; padding:0; border:0; border-radius:0; background:rgba(0,0,0,0.38); }
  .app-header { justify-content:space-between; flex-basis:56px; height:56px; padding:0 12px; }
  .app-main { padding:10px; }
}
</style>
