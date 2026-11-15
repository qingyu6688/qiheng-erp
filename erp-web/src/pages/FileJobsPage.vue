<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fileJobs, fileJob, createImport, importErrors, exportDownloadTicket, retryImport } from '../api/file-jobs'
import { downloadFile } from '../api/files'
import { allRecords } from '../api/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { importSpecs, jobStatus, csvCell } from '../types/file-jobs'
import type { FileJob, ImportError } from '../types/file-jobs'
import { documentLink, reportPermission, reportSpecs } from '../types/reporting'
import type { DataRow } from '../types/admin'
import { useSessionStore } from '../stores/session'
const route = useRoute(), session = useSessionStore(), tab = ref<'import' | 'export'>('import')
const allowedImports = computed(() => importSpecs.filter(item => session.can('import:' + item.type) && session.can(item.permission) && (item.type !== 'opening_stock' || session.can('cost:view')))), canExport = computed(() => reportSpecs.some(item => session.can(reportPermission(item.type, 'export'))))
const type = ref(''), file = ref<File>(), fileInput = ref<HTMLInputElement>(), warehouseId = ref(''), businessDate = ref(''), warehouses = ref<DataRow[]>([])
const rows = ref<FileJob[]>([]), selected = ref<FileJob>(), errors = ref<ImportError[]>([]), errorNext = ref<string | null>(null), error = ref(''), busy = ref(false), loading = ref(false)
const cursors = ref<(string | null)[]>([null]), next = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined, revision = 0, listRevision = 0, detailRevision = 0, key = '', signature = ''
let pageRequests = new AbortController(), listRequest: AbortController | undefined, detailRequest: AbortController | undefined
const errorsBusy = ref(false)
const retryKeys = new Map<string, string>()
function title(job: FileJob): string { return importSpecs.find(item => item.type === job.jobType)?.title ?? reportSpecs.find(item => item.type.replaceAll('-', '_') === job.exportType)?.title ?? '文件任务' }
function targetLink(target: { targetType: string; targetId: string }): string | undefined { return target.targetType === 'sku' ? '/skus' : target.targetType === 'partner' ? '/partners' : documentLink(target.targetType, target.targetId) }
function changedFile(event: Event): void { file.value = (event.target as HTMLInputElement).files?.[0]; key = ''; signature = '' }
async function list(): Promise<void> {
  listRequest?.abort(); listRequest = new AbortController()
  const current = revision, request = ++listRevision; loading.value = true
  try { const page = await fileJobs(tab.value, { limit: 20, cursor: cursors.value.at(-1) ?? undefined }, listRequest.signal); if (current === revision && request === listRevision) { rows.value = page.items; next.value = page.nextCursor } }
  catch (cause: unknown) { if (current === revision && request === listRevision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision && request === listRevision) loading.value = false }
}
async function select(id: string, polling = false): Promise<void> {
  detailRequest?.abort(); detailRequest = new AbortController()
  const current = revision, request = ++detailRevision, signal = detailRequest.signal; error.value = ''
  if (!polling) { selected.value = undefined; errors.value = []; errorNext.value = null }
  try {
    const value = await fileJob(tab.value, id, signal); if (current !== revision || request !== detailRevision) return
    const previousErrors = selected.value?.failedRows; selected.value = value
    if (tab.value === 'import' && value.failedRows && (!polling || previousErrors !== value.failedRows)) { const page = await importErrors(id, undefined, signal); if (current === revision && request === detailRevision) { errors.value = page.items; errorNext.value = page.nextCursor } }
  } catch (cause: unknown) { if (current === revision && request === detailRevision && !isCancelled(cause)) { selected.value = undefined; errors.value = []; errorNext.value = null; error.value = messageOf(cause) } }
}
function schedule(): void {
  clearTimeout(timer)
  const current = revision
  timer = setTimeout(async () => {
    if (current !== revision) return
    if (!rows.value.some(row => ['pending', 'running'].includes(row.status)) && !selected.value?.status.match(/^(pending|running)$/)) return
    await list(); if (current !== revision) return; if (selected.value) await select(selected.value.id, true); if (current === revision && !error.value) schedule()
  }, 2000)
}
async function refresh(): Promise<void> { const current = revision; error.value = ''; await list(); if (current !== revision) return; if (selected.value) await select(selected.value.id, true); if (current === revision) schedule() }
async function paginate(forward: boolean): Promise<void> { if (loading.value || (!forward && cursors.value.length === 1)) return; if (forward && next.value) cursors.value.push(next.value); else if (!forward) cursors.value.pop(); await refresh() }
async function submit(): Promise<void> {
  if (!file.value || busy.value) { if (!file.value) error.value = '请选择 CSV 文件'; return }
  if (!/\.csv$/i.test(file.value.name) || file.value.size > 20 * 1024 * 1024) { error.value = '请选择不超过 20MB 的 CSV 文件'; return }
  if (type.value === 'opening_stock' && (!warehouseId.value || !businessDate.value)) { error.value = '请选择期初仓库和业务日期'; return }
  const currentSignature = [session.companyId, type.value, file.value.name, file.value.size, file.value.lastModified, warehouseId.value, businessDate.value].join('|')
  if (currentSignature !== signature) { signature = currentSignature; key = newIdempotencyKey() }
  const data = new FormData(); data.set('jobType', type.value); data.set('file', file.value)
  if (type.value === 'opening_stock') { data.set('warehouseId', warehouseId.value); data.set('businessDate', businessDate.value) }
  const current = revision; busy.value = true; error.value = ''
  try { const job = await createImport(data, key, pageRequests.signal); if (current !== revision) return; key = ''; signature = ''; file.value = undefined; if (fileInput.value) fileInput.value.value = ''; await list(); if (current !== revision) return; await select(job.id); if (current === revision) schedule() }
  catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) busy.value = false }
}
async function download(url: string, filename: string): Promise<void> { const current = revision; error.value = ''; try { await downloadFile(url, filename, true, pageRequests.signal) } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) } }
async function downloadExport(job: FileJob): Promise<void> { const current = revision; error.value = ''; try { const ticket = await exportDownloadTicket(job.id, pageRequests.signal); if (current === revision) await downloadFile(ticket.url, title(job) + '.csv', true, pageRequests.signal) } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) } }
async function retry(job: FileJob): Promise<void> {
  if (!job.canRetry || busy.value) return
  const current = revision, key = retryKeys.get(job.id) ?? newIdempotencyKey(); retryKeys.set(job.id, key); busy.value = true; error.value = ''
  try { await retryImport(job.id, key, pageRequests.signal); if (current !== revision) return; retryKeys.delete(job.id); await list(); if (current !== revision) return; await select(job.id); if (current === revision) schedule() }
  catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) busy.value = false }
}
async function moreErrors(): Promise<void> {
  if (!selected.value || !errorNext.value || errorsBusy.value) return
  const current = revision, request = detailRevision, id = selected.value.id; errorsBusy.value = true
  try { const page = await importErrors(id, errorNext.value, pageRequests.signal); if (current === revision && request === detailRevision) { errors.value.push(...page.items); errorNext.value = page.nextCursor } }
  catch (cause: unknown) { if (current === revision && request === detailRevision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) errorsBusy.value = false }
}
async function downloadErrors(): Promise<void> {
  if (!selected.value || busy.value) return
  const current = revision, id = selected.value.id
  busy.value = true; error.value = ''
  try {
    const all: ImportError[] = []; const visited = new Set<string>(); let cursor: string | undefined
    do { const page = await importErrors(id, cursor, pageRequests.signal); if (current !== revision) return; all.push(...page.items); cursor = page.nextCursor ?? undefined; if (cursor && visited.has(cursor)) throw new Error('错误清单分页异常'); if (cursor) visited.add(cursor) } while (cursor && all.length <= 10000)
    if (cursor) throw new Error('错误清单超出下载上限，请分批导入')
    const content = '\uFEFF' + [['行号', '列名', '错误原因'], ...all.map(row => [row.rowNo, row.columnName, row.message])].map(row => row.map(csvCell).join(',')).join('\r\n')
    const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' })), link = document.createElement('a'); link.href = url; link.download = '导入错误清单.csv'; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) busy.value = false }
}
async function initialize(): Promise<void> {
  const current = ++revision; clearTimeout(timer); pageRequests.abort(); listRequest?.abort(); detailRequest?.abort(); pageRequests = new AbortController(); retryKeys.clear()
  rows.value = []; selected.value = undefined; errors.value = []; errorNext.value = null; error.value = ''; busy.value = false; errorsBusy.value = false; loading.value = false; cursors.value = [null]; next.value = null; key = ''; signature = ''; file.value = undefined
  if (fileInput.value) fileInput.value.value = ''
  type.value = allowedImports.value[0]?.type ?? ''; warehouses.value = []; warehouseId.value = ''; businessDate.value = new Date().toLocaleDateString('sv-SE')
  if (session.can('warehouse:view')) { try { const values = await allRecords('warehouses', true); if (current === revision) warehouses.value = values } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) } }
  if (current !== revision) return
  await list(); if (current !== revision) return; const id = route.query.jobId; if (typeof id === 'string' && /^[1-9][0-9]*$/.test(id)) await select(id); if (current === revision) schedule()
}
watch(() => route.query.tab, value => { tab.value = value === 'export' && canExport.value || !allowedImports.value.length ? 'export' : 'import' }, { immediate: true })
watch(() => [session.companyId, tab.value], () => { void initialize() }, { immediate: true })
watch(() => route.query.jobId, id => { if (typeof id === 'string' && /^[1-9][0-9]*$/.test(id)) void select(id) })
onBeforeUnmount(() => { ++revision; clearTimeout(timer); pageRequests.abort(); listRequest?.abort(); detailRequest?.abort() })
</script>
<template>
  <div class="page-heading"><div><h1>导入与导出</h1><p>任务在后台处理，仅显示当前公司中由本人创建且仍有权访问的任务。</p></div><el-button :loading="loading" @click="refresh">刷新任务</el-button></div>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <section class="panel"><el-radio-group v-model="tab" aria-label="文件任务类型"><el-radio-button v-if="allowedImports.length" value="import">数据导入</el-radio-button><el-radio-button v-if="canExport" value="export">报表导出</el-radio-button></el-radio-group>
    <form v-if="tab === 'import' && allowedImports.length" class="filters import-form" @submit.prevent="submit">
      <div class="filter-field"><label for="import-type">导入内容</label><el-select id="import-type" v-model="type" :disabled="busy"><el-option v-for="item in allowedImports" :key="item.type" :value="item.type" :label="item.title" /></el-select></div>
      <el-button :disabled="busy" @click="download('/import-templates/' + type, '导入模板-' + type + '.csv')">下载模板</el-button>
      <template v-if="type === 'opening_stock'"><div class="filter-field"><label for="import-warehouse">期初仓库</label><el-select id="import-warehouse" v-model="warehouseId" filterable :disabled="busy"><el-option v-for="row in warehouses" :key="row.id" :value="row.id" :label="row.name ?? row.code" /></el-select></div><div class="filter-field"><label for="import-date">业务日期</label><input id="import-date" v-model="businessDate" type="date" required :disabled="busy" /></div></template>
      <div class="filter-field"><label for="import-file">CSV 文件</label><input id="import-file" ref="fileInput" type="file" accept=".csv,text/csv" :disabled="busy" @change="changedFile" /></div><el-button native-type="submit" type="primary" :loading="busy">开始导入</el-button>
    </form><p v-if="tab === 'import'">使用 UTF-8 编码 CSV，每份最多 10,000 行、20MB。库存期初每个有效行生成一张草稿，需在库存调整中审核过账；错误行修正后另存文件重传。</p><p v-else>在各报表中选择条件并点击“导出报表”。完成后 24 小时内可下载 CSV 文件。</p>
  </section>
  <section class="panel table-panel"><el-table :data="rows" row-key="id"><el-table-column label="内容" min-width="160"><template #default="{ row }">{{ title(row) }}</template></el-table-column><el-table-column label="状态" min-width="120"><template #default="{ row }">{{ jobStatus[row.status] ?? row.status }}</template></el-table-column><el-table-column label="处理结果" min-width="190"><template #default="{ row }"><template v-if="tab === 'import'">成功 {{ row.successRows ?? 0 }} / 错误 {{ row.failedRows ?? 0 }} / 总行 {{ row.totalRows ?? 0 }}</template><template v-else>{{ row.rowCount ?? 0 }} 行</template></template></el-table-column><el-table-column prop="createdAt" label="创建时间" min-width="220" /><el-table-column label="操作" min-width="160"><template #default="{ row }"><el-button text @click="select(row.id)">查看结果</el-button><el-button v-if="tab === 'import' && row.canRetry" text :loading="busy" @click="retry(row)">重试未完成行</el-button><el-button v-if="tab === 'export' && row.status === 'succeeded'" text @click="downloadExport(row)">下载文件</el-button></template></el-table-column><template #empty>{{ loading ? '正在加载…' : error ? '加载失败，请刷新' : '暂无任务' }}</template></el-table><footer class="table-footer"><span>第 {{ cursors.length }} 页</span><div><el-button :disabled="loading || cursors.length === 1" @click="paginate(false)">上一页</el-button><el-button :disabled="loading || !next" @click="paginate(true)">下一页</el-button></div></footer></section>
  <section v-if="selected" class="panel"><h2>处理结果 · {{ title(selected) }}</h2><p>{{ jobStatus[selected.status] ?? selected.status }}<template v-if="selected.expireAt"> · 文件到期 {{ selected.expireAt }}</template></p><el-alert v-if="selected.errorMessage" :title="selected.errorMessage" type="warning" :closable="false" /><el-button v-if="tab === 'import' && selected.canRetry" :loading="busy" @click="retry(selected)">重试未完成行</el-button><p v-if="selected.targets?.length">成功记录（最多显示前 200 条）</p><div class="import-targets"><template v-for="target in selected.targets ?? []" :key="target.rowNo"><router-link v-if="targetLink(target)" class="text-link" :to="targetLink(target)!">第 {{ target.rowNo }} 行 · 查看{{ target.targetType === 'stock_adjustment' ? '期初草稿' : '资料' }}</router-link></template></div>
    <template v-if="errors.length"><div class="page-heading"><h3>错误行</h3><el-button :loading="busy" @click="downloadErrors">下载错误清单</el-button></div><el-table :data="errors"><el-table-column prop="rowNo" label="行号" width="90" /><el-table-column prop="columnName" label="列名" min-width="150" /><el-table-column prop="message" label="错误原因" min-width="260" /></el-table><el-button v-if="errorNext" :loading="errorsBusy" @click="moreErrors">加载更多错误</el-button></template>
  </section>
</template>
<style scoped>.panel{margin-bottom:20px}.panel:not(.table-panel){padding:20px 24px}.panel h2{margin-top:0;font-size:18px}.import-form{margin-top:20px;padding:0}.import-targets{display:flex;gap:16px;flex-wrap:wrap}.import-targets a{padding:4px 0}</style>
