<script setup lang="ts">
import { ref, watch } from 'vue'
import { auditLogs } from '../api/audit'
import type { AuditRow } from '../api/audit'
import { isCancelled, messageOf } from '../api/http'
import { actionLabels } from '../types/sales'
import { formatDateTimeSeconds } from '../utils/format'

const props = defineProps<{
  ownerType: 'sales_order' | 'purchase_order'
  ownerId: string
  companyId: string
}>()
const items = ref<AuditRow[]>([])
const busy = ref(false)
const next = ref<string | null>(null)
const error = ref('')
let requests = new AbortController()

function operation(action: string): string {
  const verb = action.split('.').at(-1) ?? action
  if (verb === 'create') return '新建'
  return actionLabels[verb] ?? action
}

async function load(more = false): Promise<void> {
  if (busy.value || (more && !next.value)) return
  const { signal } = requests
  busy.value = true
  error.value = ''
  try {
    const result = await auditLogs(
      {
        entityType: props.ownerType,
        entityId: props.ownerId,
        companyId: props.companyId,
        cursor: more ? next.value : undefined,
        limit: 20,
      },
      signal,
    )
    if (signal.aborted) return
    items.value = more ? [...items.value, ...result.items] : result.items
    next.value = result.nextCursor
  } catch (cause: unknown) {
    if (!signal.aborted && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (!signal.aborted) busy.value = false
  }
}

watch(
  () => [props.ownerType, props.ownerId, props.companyId],
  (_, _previous, onCleanup) => {
    requests = new AbortController()
    const requestsForOrder = requests
    onCleanup(() => requestsForOrder.abort())
    items.value = []
    next.value = null
    busy.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div v-loading="busy" class="panel">
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-table v-if="items.length" :data="items" row-key="id">
      <el-table-column label="时间" min-width="165">
        <template #default="{ row }">{{ formatDateTimeSeconds(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="120">
        <template #default="{ row }">{{ operation(row.action) }}</template>
      </el-table-column>
      <el-table-column label="操作人" min-width="120">
        <template #default="{ row }">{{ row.operatorName ?? row.operatorId ?? '—' }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-else-if="!busy && !error" description="当前权限下暂无操作记录" :image-size="56" />
    <el-button v-if="error || next" :loading="busy" @click="load(!!next)">
      {{ error ? '重试' : '加载更多' }}
    </el-button>
  </div>
</template>
