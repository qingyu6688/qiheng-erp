<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { glConfig, createGlExport } from '../api/gl'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import type { GlConfigRow, GlQuery } from '../types/gl'
const props = defineProps<{ type: string; query?: GlQuery; printId?: string }>()
const session = useSessionStore(),
  router = useRouter(),
  formats = ref<GlConfigRow[]>([]),
  formatId = ref(''),
  fileType = ref('xlsx'),
  busy = ref(false),
  error = ref('')
let exportKey = newIdempotencyKey(),
  exportPayload = ''
let revision = 0
const printQuery = computed(() =>
  Object.fromEntries(
    Object.entries({
      type: props.type === 'vouchers' ? 'voucher' : props.type,
      id: props.printId,
      ...props.query,
    })
      .filter(([, value]) => value !== undefined && value !== '')
      .map(([key, value]) => [key, String(value)]),
  ),
)
watch(
  [() => session.companyId, () => props.type],
  async () => {
    const current = ++revision
    formats.value = []
    formatId.value = ''
    error.value = ''
    if (props.type === 'vouchers' && session.can('gl_export:create'))
      try {
        const data = await glConfig('export-formats')
        if (current !== revision) return
        formats.value = data
        formatId.value = data[0]?.id || ''
      } catch (e: unknown) {
        if (current === revision && !isCancelled(e)) error.value = messageOf(e)
      }
  },
  { immediate: true },
)
async function exportFile(): Promise<void> {
  const company = session.companyId
  if (!props.query) return
  busy.value = true
  error.value = ''
  try {
    const payload = {
      query: props.query,
      fileType: fileType.value,
      ...(props.type === 'vouchers' ? { formatId: formatId.value } : {}),
    }
    const serialized = JSON.stringify(payload)
    if (serialized !== exportPayload) {
      exportKey = newIdempotencyKey()
      exportPayload = serialized
    }
    const task = await createGlExport(props.type, payload, exportKey)
    if (company !== session.companyId) return
    await router.push({ path: '/file-jobs', query: { tab: 'export', jobId: task.taskId } })
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="output-actions">
    <template v-if="session.can('gl_export:create') && query"
      ><el-select
        v-if="type === 'vouchers'"
        v-model="formatId"
        style="width: 170px"
        placeholder="导出格式"
        ><el-option v-for="f in formats" :key="f.id" :value="f.id" :label="f.name" /></el-select
      ><el-select v-else v-model="fileType" style="width: 100px"
        ><el-option label="Excel" value="xlsx" /><el-option label="CSV" value="csv" /></el-select
      ><el-button
        :loading="busy"
        :disabled="!query.periodFrom || !query.periodTo || (type === 'vouchers' && !formatId)"
        @click="exportFile"
        >{{ type === 'vouchers' ? '导出本期已记账凭证' : '后台导出' }}</el-button
      ></template
    ><router-link
      v-if="session.can('gl_print:view') && (type !== 'vouchers' || printId)"
      :to="{ path: '/gl/print', query: printQuery }"
      target="_blank"
      ><el-button>打印预览</el-button></router-link
    ><span v-if="error" role="alert">{{ error }}</span>
  </div>
</template>
<style scoped>
.output-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.output-actions > span {
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
