<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { attachments, uploadAttachment } from '../../api/documents'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import type { Attachment } from '../../types/documents'
import { useSessionStore } from '../../stores/session'

const props = defineProps<{ shipmentId: string; modelValue: string[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]]; busy: [value: boolean] }>()
const session = useSessionStore(),
  rows = ref<Attachment[]>([]),
  loading = ref(false),
  uploading = ref(false)
const file = ref<File>(),
  fileInput = ref<HTMLInputElement>(),
  error = ref('')
const writable = computed(() => session.can('shipment:update'))
const controller = new AbortController()
let active = true,
  uploadKey = ''
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await attachments('shipment', props.shipmentId, controller.signal)
    if (active) rows.value = result
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) loading.value = false
  }
}
function choose(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0]
  uploadKey = newIdempotencyKey()
}
async function upload(): Promise<void> {
  if (!file.value || uploading.value || props.disabled || !writable.value) return
  if (file.value.size > 20 * 1024 * 1024 || !/\.(pdf|jpe?g|png|xlsx|csv)$/i.test(file.value.name)) {
    error.value = '请选择 PDF、JPG、PNG、XLSX 或 CSV 文件，单个不超过 20MB'
    return
  }
  const data = new FormData()
  data.set('ownerType', 'shipment')
  data.set('ownerId', props.shipmentId)
  data.set('file', file.value)
  uploading.value = true
  emit('busy', true)
  error.value = ''
  try {
    const attachment = await uploadAttachment(
      data,
      uploadKey || (uploadKey = newIdempotencyKey()),
      controller.signal,
    )
    if (!active) return
    rows.value = [...rows.value.filter((row) => row.id !== attachment.id), attachment]
    emit('update:modelValue', [...new Set([...props.modelValue, attachment.id])])
    file.value = undefined
    uploadKey = ''
    if (fileInput.value) fileInput.value.value = ''
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) {
      uploading.value = false
      emit('busy', false)
    }
  }
}
onMounted(() => {
  void load()
})
onBeforeUnmount(() => {
  active = false
  controller.abort()
})
</script>
<template>
  <section class="receipt-attachments" aria-label="签收回单">
    <h3>回单（可选）</h3>
    <p>选择本发货单已有的附件，或上传后随本次签收一起登记。每张单据最多 20 个附件。</p>
    <el-checkbox-group
      :model-value="modelValue"
      :disabled="disabled || uploading"
      @update:model-value="emit('update:modelValue', $event.map(String))"
      ><el-checkbox v-for="row in rows" :key="row.id" :value="row.id">{{
        row.fileName
      }}</el-checkbox></el-checkbox-group
    >
    <p v-if="loading" role="status">正在加载发货单附件…</p>
    <p v-else-if="!rows.length && !error">本发货单暂无附件。</p>
    <div v-if="writable" class="receipt-upload">
      <label :for="'sign-receipt-' + shipmentId">上传回单</label
      ><input
        :id="'sign-receipt-' + shipmentId"
        ref="fileInput"
        type="file"
        accept=".pdf,.jpg,.jpeg,.png,.xlsx,.csv"
        :disabled="disabled || uploading || loading"
        @change="choose"
      /><el-button
        :disabled="disabled || !file || rows.length >= 20 || loading"
        :loading="uploading"
        @click="upload"
        >上传并选中</el-button
      >
    </div>
    <p v-else>当前账号可以选择已有附件，上传回单需要发货单修改权限。</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" /><el-button
      v-if="error && !loading && !uploading"
      text
      :disabled="disabled"
      @click="load"
      >重新加载回单</el-button
    >
  </section>
</template>
<style scoped>
.receipt-attachments {
  margin: 20px 0 4px;
  border-top: 1px solid var(--el-border-color-light);
  padding-top: 16px;
}
.receipt-attachments h3 {
  font-size: 14px;
  margin: 0 0 8px;
}
.receipt-attachments p {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
}
.receipt-attachments :deep(.el-checkbox) {
  display: flex;
  margin: 8px 0;
  height: auto;
}
.receipt-attachments :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.receipt-upload {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.receipt-upload > label {
  font-size: 13px;
}
.receipt-upload > input {
  min-width: 0;
  flex: 1;
}
</style>
