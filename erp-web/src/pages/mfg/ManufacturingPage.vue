<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../../stores/session'
import { manufacturingNavigation } from '../../types/manufacturing'
import type { ManufacturingArea } from '../../types/manufacturing'

const props = defineProps<{ area: ManufacturingArea }>()
const session = useSessionStore()
const route = useRoute()
const views = {
  boms: defineAsyncComponent(() => import('./BomPage.vue')),
  'work-orders': defineAsyncComponent(() => import('./WorkOrderPage.vue')),
  issues: defineAsyncComponent(() => import('./ProductionStockPage.vue')),
  receipts: defineAsyncComponent(() => import('./ProductionStockPage.vue')),
  'cost-periods': defineAsyncComponent(() => import('./CostPeriodPage.vue')),
  reports: defineAsyncComponent(() => import('./ManufacturingReports.vue')),
}
const title = computed(
  () => manufacturingNavigation.find((entry) => entry.area === props.area)?.title,
)
const viewKey = computed(() => `${session.companyId ?? ''}:${route.fullPath}`)
</script>

<template>
  <div class="mfg-workspace">
    <div class="page-heading">
      <h1>{{ title }}</h1>
    </div>
    <component :is="views[area]" :key="viewKey" :area="area" />
  </div>
</template>

<style>
.mfg-panel {
  padding: 16px;
  min-width: 0;
}
.mfg-workspace .mfg-panel + .mfg-panel {
  margin-top: 14px;
}
.mfg-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.mfg-toolbar .el-button {
  margin-left: 0;
}
.mfg-toolbar .el-input,
.mfg-toolbar .el-select {
  width: min(210px, 100%);
}
.mfg-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
  gap: 0 14px;
}
.mfg-form .el-select,
.mfg-form .el-date-editor {
  width: 100%;
}
.mfg-form-extra {
  margin-block: 14px;
}
.mfg-list-pager {
  margin-top: 12px;
}
.mfg-lines {
  border: 1px solid var(--el-border-color);
  padding: 12px;
  margin-bottom: 8px;
  border-radius: var(--el-border-radius-base);
}
</style>
