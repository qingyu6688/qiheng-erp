<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { workflowProgress } from '../../api/workflow'
import { isCancelled, messageOf } from '../../api/http'
import type { WorkflowBusinessType, WorkflowProgress } from '../../types/workflow'
import { workflowStatus } from '../../types/workflow'
import { useSessionStore } from '../../stores/session'
import { formatDateTime } from '../../utils/format'
const props = defineProps<{
  businessType?: WorkflowBusinessType
  businessId: string
  instanceId?: string
}>()
const session = useSessionStore(),
  value = ref<WorkflowProgress>(),
  error = ref(''),
  loading = ref(false)
let revision = 0,
  timer: ReturnType<typeof setTimeout> | undefined
async function load(): Promise<void> {
  const current = ++revision
  if (!props.businessType || !props.instanceId || props.instanceId === '0') {
    value.value = undefined
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await workflowProgress(props.businessType, props.businessId)
    if (current === revision) value.value = result
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      value.value = undefined
    }
  } finally {
    if (current === revision) loading.value = false
  }
}
function refresh(): void {
  clearTimeout(timer)
  timer = setTimeout(() => {
    void load()
  }, 400)
}
watch(
  () => [props.businessType, props.businessId, props.instanceId, session.companyId],
  () => {
    value.value = undefined
    void load()
  },
  { immediate: true },
)
window.addEventListener('erp:messages-changed', refresh)
onScopeDispose(() => {
  ++revision
  clearTimeout(timer)
  window.removeEventListener('erp:messages-changed', refresh)
})
</script>
<template>
  <section v-if="instanceId && instanceId !== '0'" class="panel flow-progress" :aria-busy="loading">
    <div class="page-heading">
      <div>
        <h2>审批记录</h2>
        <p v-if="value?.instances[0]">
          {{ workflowStatus[value.instances[0].status] }} · 发布版本
          {{ value.instances[0].modelVersion }} · 发起于
          {{ formatDateTime(value.instances[0].startedAt) }}
        </p>
      </div>
      <router-link
        v-if="value?.instances[0] && value.canOpen"
        class="text-link"
        :to="'/workflow/instances/' + value.instances[0].id"
        >进入审批中心</router-link
      ><el-button v-else :loading="loading" @click="load">刷新进度</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-table
      v-if="value"
      :data="value.tasks"
      row-key="id"
      ><el-table-column
        prop="nodeName"
        label="环节"
        min-width="180"
        show-overflow-tooltip
      /><el-table-column prop="assigneeName" label="处理人" min-width="130" /><el-table-column
        label="状态"
        min-width="110"
        ><template #default="{ row }">{{
          workflowStatus[row.status] ?? row.status
        }}</template></el-table-column
      ><el-table-column
        prop="comment"
        label="审批意见"
        min-width="220"
        show-overflow-tooltip
      /><el-table-column label="处理时间" min-width="180"
        ><template #default="{ row }">{{
          row.endedAt ? formatDateTime(row.endedAt) : '—'
        }}</template></el-table-column
      ></el-table
    >
    <p v-if="value && value.instances.length > 1" class="muted">
      本单已有 {{ value.instances.length }} 次流程，以上为最近一次记录。
    </p>
  </section>
</template>
