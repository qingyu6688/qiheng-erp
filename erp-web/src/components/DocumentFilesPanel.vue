<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { attachments, uploadAttachment, deleteAttachment } from '../api/documents'
import { downloadFile } from '../api/files'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { documentPermission } from '../types/documents'
import type { Attachment } from '../types/documents'
import { useSessionStore } from '../stores/session'
const props = defineProps<{ ownerType: string; ownerId: string }>(), session = useSessionStore()
const rows = ref<Attachment[]>([]), file = ref<File>(), input = ref<HTMLInputElement>(), error = ref(''), busy = ref(false), loading = ref(false)
const permission = computed(() => documentPermission(props.ownerType)), writable = computed(() => session.can(permission.value + ':' + (['document_reversal', 'invoice_registration'].includes(props.ownerType) ? 'create' : 'update')))
let revision = 0, listRevision = 0, uploadKey = '', deleteKeys = new Map<string, string>(), controller = new AbortController()
async function load(): Promise<void> {
  const current = revision, sequence = ++listRevision; loading.value = true; error.value = ''
  try { const values = await attachments(props.ownerType, props.ownerId, controller.signal); if (current === revision && sequence === listRevision) rows.value = values }
  catch (cause: unknown) { if (current === revision && sequence === listRevision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision && sequence === listRevision) loading.value = false }
}
function choose(event: Event): void { file.value = (event.target as HTMLInputElement).files?.[0]; uploadKey = newIdempotencyKey() }
async function upload(): Promise<void> {
  if (!file.value || busy.value || !writable.value) return
  if (file.value.size > 20 * 1024 * 1024 || !/\.(pdf|jpe?g|png|xlsx|csv)$/i.test(file.value.name)) { error.value = '请选择 PDF、JPG、PNG、XLSX 或 CSV 文件，单个不超过 20MB'; return }
  const data = new FormData(); data.set('ownerType', props.ownerType); data.set('ownerId', props.ownerId); data.set('file', file.value)
  const current = revision; busy.value = true; error.value = ''
  try { await uploadAttachment(data, uploadKey || (uploadKey = newIdempotencyKey()), controller.signal); if (current !== revision) return; file.value = undefined; uploadKey = ''; if (input.value) input.value.value = ''; await load() }
  catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) busy.value = false }
}
async function remove(row: Attachment): Promise<void> {
  if (busy.value || !writable.value) return
  busy.value = true; error.value = ''; const current = revision
  const key = deleteKeys.get(row.id) ?? newIdempotencyKey(); deleteKeys.set(row.id, key)
  try { await deleteAttachment(row.id, key, controller.signal); if (current !== revision) return; deleteKeys.delete(row.id); await load() }
  catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) busy.value = false }
}
async function download(row: Attachment): Promise<void> {
  const current = revision
  try { await downloadFile('/attachments/' + row.id + '/download', row.fileName, true, controller.signal) }
  catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
}
watch(() => [props.ownerType, props.ownerId, session.companyId], () => {
  controller.abort(); controller = new AbortController(); revision++; rows.value = []; file.value = undefined; busy.value = false; uploadKey = ''; deleteKeys = new Map()
  if (input.value) input.value.value = ''
  void load()
}, { immediate: true })
onScopeDispose(() => { revision++; controller.abort() })
</script>
<template>
  <section class="panel document-files"><div class="page-heading"><div><h2>单据附件</h2><p>每张单据最多 20 个文件；下载时重新检查权限。</p></div><router-link v-if="session.can(permission + ':print')" class="text-link" :to="'/print/' + ownerType + '/' + ownerId">打印预览</router-link></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <form v-if="writable" class="filters" @submit.prevent="upload"><div class="filter-field"><label :for="'document-file-' + ownerId">添加附件</label><input :id="'document-file-' + ownerId" ref="input" type="file" accept=".pdf,.jpg,.jpeg,.png,.xlsx,.csv" :disabled="busy" @change="choose" /></div><el-button native-type="submit" :disabled="!file || rows.length >= 20" :loading="busy">上传附件</el-button></form>
    <ul v-if="rows.length" class="attachment-list"><li v-for="row in rows" :key="row.id"><span>{{ row.fileName }} <small>{{ Math.ceil(Number(row.sizeBytes) / 1024) }} KB</small></span><div><el-button text @click="download(row)">下载</el-button><el-popconfirm v-if="writable" title="确定删除该附件？" confirm-button-text="删除" cancel-button-text="保留" @confirm="remove(row)"><template #reference><el-button text type="danger" :disabled="busy">删除</el-button></template></el-popconfirm></div></li></ul><p v-else>{{ loading ? '正在加载附件…' : error ? '附件加载失败，可重试' : '暂无附件' }}</p><el-button v-if="error && !loading" text @click="load">重新加载附件</el-button>
  </section>
</template>
<style scoped>.document-files{padding:20px 24px;margin-top:20px}.document-files .filters{padding:0;margin:16px 0}.attachment-list{list-style:none;margin:0;padding:0}.attachment-list li{display:flex;justify-content:space-between;align-items:center;gap:16px;border-bottom:1px solid var(--el-border-color-lighter);padding:8px 0}.attachment-list small{color:var(--el-text-color-secondary);margin-left:12px}.document-files h2{margin:0}</style>
