<script setup lang="ts">
import { previewRoute, canReturnPreview, closeDocumentPreview, returnDocumentPreview } from './document-preview'
import DemoDocumentContent from './DemoDocumentContent.vue'
</script>

<template>
  <el-drawer
    :model-value="Boolean(previewRoute)"
    :title="String(previewRoute?.meta.title ?? '单据') + ' · 只读详情'"
    direction="rtl"
    size="min(1180px, 94vw)"
    class="demo-document-drawer"
    destroy-on-close
    @close="closeDocumentPreview"
  >
    <div class="preview-toolbar">
      <span>关闭后保留列表筛选与浏览位置</span>
      <el-button v-if="canReturnPreview" @click="returnDocumentPreview">返回上一项</el-button>
      <el-button @click="closeDocumentPreview">关闭详情</el-button>
    </div>
    <DemoDocumentContent v-if="previewRoute" :key="previewRoute.path" :route="previewRoute" />
  </el-drawer>
</template>

<style scoped>
.preview-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; color: #708199; font-size: 12px; }
.preview-toolbar > span { margin-right: auto; }
:global(.demo-document-drawer .document-page-heading .back-link) { display: none; }
:global(.demo-document-drawer .el-drawer__header) { margin-bottom: 0; padding-bottom: 16px; border-bottom: 1px solid #e8edf3; color: #1e304c; }
:global(.demo-document-drawer .el-drawer__body) { background: #f5f7fa; }
@media (max-width: 640px) { :global(.demo-document-drawer) { width: 100% !important; } }
</style>
