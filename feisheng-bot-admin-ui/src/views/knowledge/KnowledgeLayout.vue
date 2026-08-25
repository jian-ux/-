<template>
  <section class="knowledge-shell">
    <nav class="knowledge-nav" aria-label="知识库管理">
      <el-tabs :model-value="route.path" @tab-change="openSection">
        <el-tab-pane
          v-for="section in sections"
          :key="section.path"
          :label="section.label"
          :name="section.path"
        />
      </el-tabs>
    </nav>
    <router-view />
  </section>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const sections = [
  { path: '/knowledge/faq', label: '常见问题' },
  { path: '/knowledge/upload', label: '文档上传' },
  { path: '/knowledge/semantic-units', label: '结构化审核' },
  { path: '/knowledge/quality-audit', label: '质量审计' },
  { path: '/knowledge/unmatched', label: '问题改进池' }
]

function openSection(path) {
  if (path && path !== route.path) router.push(path)
}
</script>

<style scoped>
.knowledge-shell { min-width:0; }
.knowledge-nav { margin:-4px 0 14px; padding:0 16px; border:1px solid #e2e7ef; border-radius:6px; background:#fff; }
.knowledge-nav :deep(.el-tabs__header) { margin:0; }
.knowledge-nav :deep(.el-tabs__nav-wrap::after) { height:1px; background:#edf0f5; }
.knowledge-nav :deep(.el-tabs__item) { height:50px; padding:0 18px; }
@media (max-width: 720px) {
  .knowledge-nav { margin:0 0 10px; padding:0 10px; overflow:hidden; }
  .knowledge-nav :deep(.el-tabs__item) { height:46px; padding:0 14px; }
}
</style>
