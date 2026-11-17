<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { isCancelled, messageOf } from '../api/http'
import { wmsWarehouses } from '../api/wms'
import { useSessionStore } from '../stores/session'
import type { Id } from '../types/api'
import type { WmsWarehouse } from '../types/wms'
import WmsOutbound from './wms/WmsOutbound.vue'
import WmsSettings from './wms/WmsSettings.vue'
import WmsStockWork from './wms/WmsStockWork.vue'

const session = useSessionStore()
const warehouse = ref<Id>('')
const warehouses = ref<WmsWarehouse[]>([])
const error = ref('')
const loading = ref(false)
const tabs = [
  { id: 'picks', name: '拣货', required: ['wms_task:view'] },
  { id: 'waves', name: '波次', required: ['wms_task:view', 'wms_wave:view'] },
  { id: 'packs', name: '复核装箱', required: ['wms_pack:view'] },
  { id: 'moves', name: '手工移库', required: ['wms_move:view'] },
  { id: 'replenishments', name: '补货', required: ['wms_move:view'] },
  { id: 'counts', name: '库位盘点', required: ['wms_task:view', 'stock_count:view'] },
  { id: 'rules', name: '库位规则', required: ['wms_task:configure'] },
  { id: 'tasks', name: '任务分配与统计', required: ['wms_task:view'] },
  { id: 'labels', name: '标签打印', required: ['label:print'] },
] as const
const visible = computed(() =>
  tabs.filter((tab) => tab.required.every((permission) => session.can(permission))),
)
type Area = (typeof tabs)[number]['id']
const selectedTab = ref<Area>('picks')
const area = computed(
  () => visible.value.find((item) => item.id === selectedTab.value)?.id ?? visible.value[0]?.id,
)

watch(
  () => session.companyId,
  async (company, previous, onCleanup) => {
    if (company === previous) return
    const scope = new AbortController()
    onCleanup(() => scope.abort())
    warehouse.value = ''
    warehouses.value = []
    error.value = ''
    loading.value = !!company
    if (!company) return
    try {
      const result = await wmsWarehouses(scope.signal)
      if (!scope.signal.aborted) {
        warehouses.value = result.items
        warehouse.value = result.items[0]?.id ?? ''
      }
    } catch (cause: unknown) {
      if (!scope.signal.aborted && !isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      if (!scope.signal.aborted) loading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-heading"><h1>仓储作业</h1></div>
  <section class="panel work-panel" aria-label="仓储作业">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="warehouse-filter">
      <label>当前仓库</label>
      <el-select
        v-model="warehouse"
        aria-label="当前仓库"
        :loading="loading"
        placeholder="请选择仓库"
        filterable
      >
        <el-option
          v-for="item in warehouses"
          :key="item.id"
          :value="item.id"
          :label="item.code + ' · ' + item.name"
        />
      </el-select>
    </div>
    <p v-if="loading" role="status">正在读取仓库。</p>
    <el-empty v-else-if="!warehouse" description="当前公司没有授权的启用仓库" />
    <template v-else-if="area">
      <el-tabs :model-value="area" @update:model-value="selectedTab = $event as Area">
        <el-tab-pane v-for="tab in visible" :key="tab.id" :name="tab.id" :label="tab.name" />
      </el-tabs>
      <WmsOutbound
        v-if="area === 'picks' || area === 'waves' || area === 'packs'"
        :key="warehouse + area"
        :warehouse="warehouse"
        :area="area"
      />
      <WmsStockWork
        v-else-if="area === 'moves' || area === 'replenishments' || area === 'counts'"
        :key="warehouse + area"
        :warehouse="warehouse"
        :area="area"
      />
      <WmsSettings v-else :key="warehouse + area" :warehouse="warehouse" :area="area" />
    </template>
  </section>
</template>

<style scoped>
.warehouse-filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.warehouse-filter .el-select {
  width: min(300px, 100%);
}
.work-panel {
  padding: 16px;
  min-width: 0;
}
</style>
