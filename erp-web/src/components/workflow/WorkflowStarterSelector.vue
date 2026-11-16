<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { workflowStartOptions, workflowReversalStartOptions } from '../../api/workflow'
import { isCancelled, messageOf } from '../../api/http'
import type { StartOptions, WorkflowBusinessType, WorkflowSelections } from '../../types/workflow'
import { useSessionStore } from '../../stores/session'
const props = defineProps<{
  businessType: WorkflowBusinessType
  businessId?: string
  preview?: { sourceType: string; sourceId: string; businessDate: string }
  modelValue: WorkflowSelections
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: WorkflowSelections]
  ready: [value: boolean]
}>()
const session = useSessionStore(),
  nodes = ref<StartOptions['nodes']>([]),
  loading = ref(true),
  error = ref('')
let revision = 0
const ready = computed(
  () =>
    !loading.value &&
    !error.value &&
    nodes.value.every(
      (node) =>
        props.modelValue[node.id]?.length &&
        props.modelValue[node.id]!.every((id) => node.candidates.some((user) => user.id === id)),
    ),
)
watch(ready, (value) => emit('ready', value), { immediate: true })
async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  nodes.value = []
  emit('ready', false)
  try {
    const result = await (props.preview
      ? workflowReversalStartOptions(
          props.preview.sourceType,
          props.preview.sourceId,
          props.preview.businessDate,
        )
      : workflowStartOptions(props.businessType, props.businessId!))
    if (current !== revision) return
    nodes.value = result.nodes
    const next: WorkflowSelections = {}
    for (const node of result.nodes)
      next[node.id] = (props.modelValue[node.id] ?? []).filter((id) =>
        node.candidates.some((user) => user.id === id),
      )
    emit('update:modelValue', next)
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
function update(id: string, users: string[]): void {
  emit('update:modelValue', { ...props.modelValue, [id]: users })
}
watch(
  () =>
    [
      props.businessType,
      props.businessId,
      props.preview?.sourceType,
      props.preview?.sourceId,
      props.preview?.businessDate,
      session.companyId,
    ].join('|'),
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
})
</script>
<template>
  <p v-if="loading" role="status">正在核对流程和自选处理人…</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button
    v-if="error"
    :disabled="disabled"
    @click="load"
    >重新获取流程选项</el-button
  >
  <section v-if="nodes.length" class="flow-start-options">
    <h3>发起人自选处理人</h3>
    <el-form label-position="top" :disabled="disabled"
      ><el-form-item v-for="node in nodes" :key="node.id" :label="node.name"
        ><el-select
          :model-value="modelValue[node.id] ?? []"
          multiple
          filterable
          @update:model-value="(users: string[]) => update(node.id, users)"
          ><el-option
            v-for="user in node.candidates"
            :key="user.id"
            :value="user.id"
            :label="user.name"
        /></el-select>
        <p v-if="!node.candidates.length" class="muted">
          暂无符合当前单据权限与复核规则的人员，请联系管理员调整。
        </p></el-form-item
      ></el-form
    >
  </section>
</template>
