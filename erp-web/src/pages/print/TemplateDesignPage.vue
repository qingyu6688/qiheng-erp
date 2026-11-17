<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  createPrintPdf,
  printableDocuments,
  printDesignPreview,
  printFields,
  printTemplate,
  printTemplateTypes,
  printTemplates,
  savePrintTemplate,
} from '../../api/print-design'
import type { PrintableDocument } from '../../api/print-design'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import {
  createElement,
  emptyTemplate,
  paperDimensions,
  printElementLabels,
} from '../../types/print-design'
import type {
  PrintDesignField,
  PrintDocumentType,
  PrintElement,
  PrintElementType,
  PrintFormat,
  PrintModel,
  PrintSection,
  PrintTemplateInput,
  PrintTemplateSummary,
} from '../../types/print-design'
import PrintCanvas from './PrintCanvas.vue'

const session = useSessionStore()
const docs = ref<PrintDocumentType[]>([])
const templates = ref<PrintTemplateSummary[]>([])
const cursor = ref<string | null>(null)
const filter = ref('')
const templateId = ref<string>()
const draft = ref<PrintTemplateInput>()
const definitions = ref<PrintDesignField[]>([])
const section = ref<PrintSection>('header')
const selectedIndex = ref<number>()
const snap = ref(true)
const preview = ref<PrintModel>()
const previewDocument = ref('')
const previewDocumentChoice = ref<{ id: string; value: string }>()
const previewVisible = ref(false)
const batchVisible = ref(false)
const batchIds = ref('')
const batchSelection = ref<string[]>([])
const batchChoices = ref<PrintableDocument[]>([])
const batchSearching = ref(false)
const copies = ref(1)
const taskId = ref('')
const error = ref('')
const notice = ref('')
const pending = ref(false)
const initialized = ref(false)
const canManage = computed(() => session.can('print_template:manage'))
const canEdit = computed(
  () => canManage.value && (!draft.value?.shared || session.can('print_template:tenant_manage')),
)
const headings = Object.entries(printElementLabels) as [PrintElementType, string][]
const formats: Array<{ value: PrintFormat; title: string }> = [
  { value: 'text', title: '文本' },
  { value: 'quantity', title: '数量' },
  { value: 'money', title: '金额' },
  { value: 'datetime', title: '日期时间' },
]
const docFields = computed(() => definitions.value.filter((field) => !field.line))
const lineFields = computed(() => definitions.value.filter((field) => field.line))
const dimension = computed(() =>
  draft.value ? paperDimensions(draft.value.layout.paper) : { width: 210, height: 297 },
)
const authoringModel = computed<PrintModel>(() => ({
  ...dimension.value,
  background: draft.value?.layout.background,
  pages: [{ items: [] }],
}))
const elements = computed(() => draft.value?.layout[section.value] ?? [])
const selected = computed(() =>
  selectedIndex.value === undefined ? undefined : elements.value[selectedIndex.value],
)
const table = computed(() => draft.value?.layout.table ?? null)
const statusLabels = { draft: '草稿', active: '启用', disabled: '停用' }
let scope = new AbortController()
let revision = 0
let listRevision = 0
let batchKey = ''
let signature = ''
let batchSearch = 0

async function forCurrent<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  done: (value: T) => void,
): Promise<void> {
  if (pending.value) return
  const current = revision
  pending.value = true
  error.value = ''
  try {
    const result = await operation(scope.signal)
    if (revision === current) done(result)
  } catch (cause: unknown) {
    if (revision === current && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (revision === current) pending.value = false
  }
}

async function list(next?: string): Promise<void> {
  const serial = ++listRevision
  const current = revision
  error.value = ''
  try {
    const page = await printTemplates(filter.value || undefined, next, scope.signal)
    if (current !== revision || serial !== listRevision) return
    templates.value = next ? [...templates.value, ...page.items] : page.items
    cursor.value = page.nextCursor
    initialized.value = true
  } catch (cause: unknown) {
    if (current === revision && serial === listRevision && !isCancelled(cause))
      error.value = messageOf(cause)
  }
}

function company(): void {
  revision++
  const current = revision
  scope.abort()
  scope = new AbortController()
  docs.value = []
  definitions.value = []
  templates.value = []
  filter.value = ''
  cursor.value = null
  draft.value = undefined
  templateId.value = undefined
  selectedIndex.value = undefined
  pending.value = false
  preview.value = undefined
  previewVisible.value = false
  batchVisible.value = false
  initialized.value = false
  previewDocument.value = ''
  previewDocumentChoice.value = undefined
  batchIds.value = ''
  batchSelection.value = []
  batchChoices.value = []
  batchSearching.value = false
  taskId.value = ''
  batchKey = ''
  signature = ''
  notice.value = ''
  if (!session.companyId) return
  void list()
  void printTemplateTypes(scope.signal)
    .then((result) => {
      if (current === revision) docs.value = result
    })
    .catch((cause: unknown) => {
      if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
    })
}

function edit(id: string): void {
  notice.value = ''
  void forCurrent(
    async (signal) => {
      const value = await printTemplate(id, signal)
      return { value, fields: await printFields(value.documentType, signal) }
    },
    ({ value, fields }) => {
      const { id: key, ...body } = value
      for (const section of [body.layout.header, body.layout.footer]) {
        for (const element of section) element.style ??= createElement(element.type).style
      }
      templateId.value = key
      draft.value = body
      definitions.value = fields
      section.value = 'header'
      selectedIndex.value = undefined
    },
  )
}

function start(type = filter.value || docs.value[0]?.id): void {
  if (!type) return
  void forCurrent(
    (signal) => printFields(type, signal),
    (fields) => {
      templateId.value = undefined
      draft.value = emptyTemplate(type)
      definitions.value = fields
      section.value = 'header'
      selectedIndex.value = undefined
      notice.value = ''
    },
  )
}

function save(): void {
  if (!draft.value || !canEdit.value) return
  const input = draft.value
  void forCurrent(
    (signal) => savePrintTemplate(templateId.value, input, signal),
    (saved) => {
      const { id, ...body } = saved
      templateId.value = id
      draft.value = body
      void list()
      notice.value = '打印模板已保存。'
    },
  )
}

function create(type: PrintElementType, field?: string, point?: { x: number; y: number }): void {
  if (!draft.value || !canEdit.value) return
  const element = createElement(type)
  if (field) element.field = field
  const offset = elements.value.length * 3
  element.x = point?.x ?? offset
  element.y =
    point?.y ?? (section.value === 'header' ? offset : dimension.value.height - 35 - offset)
  place(element)
  elements.value.push(element)
  selectedIndex.value = elements.value.length - 1
}

function place(element: PrintElement, roundToGrid = true): void {
  const margins = draft.value?.layout.paper.margins
  if (!margins) return
  const maxX = dimension.value.width - margins.left - margins.right - element.w
  const maxY = dimension.value.height - margins.top - margins.bottom - element.h
  const round = (value: number): number =>
    roundToGrid ? Math.round(value * (snap.value ? 1 : 10)) / (snap.value ? 1 : 10) : value
  element.x = Math.max(0, Math.min(maxX, round(element.x)))
  element.y = Math.max(0, Math.min(maxY, round(element.y)))
}

function fitPaper(): void {
  const layout = draft.value?.layout
  if (!layout) return
  for (const section of [layout.header, layout.footer])
    for (const element of section) place(element, false)
}

function tile(element: PrintElement): Record<string, string> {
  const margins = draft.value?.layout.paper.margins
  return {
    left: `${element.x + (margins?.left ?? 0)}mm`,
    top: `${element.y + (margins?.top ?? 0)}mm`,
    width: `${element.w}mm`,
    height: `${element.h}mm`,
  }
}

function elementName(element: PrintElement): string {
  return element.type === 'text'
    ? element.text || element.textEn || '文本'
    : docFields.value.find((field) => field.id === element.field)?.label ||
        printElementLabels[element.type]
}

let moving:
  | { index: number; x: number; y: number; clientX: number; clientY: number; ratio: number }
  | undefined
function beginMove(event: PointerEvent, element: PrintElement, index: number): void {
  selectedIndex.value = index
  const target = event.currentTarget
  if (!canEdit.value || !(target instanceof HTMLElement) || event.button !== 0) return
  target.setPointerCapture(event.pointerId)
  const sheet = target.closest('.print-design-sheet')?.getBoundingClientRect()
  if (sheet?.width)
    moving = {
      index,
      x: element.x,
      y: element.y,
      clientX: event.clientX,
      clientY: event.clientY,
      ratio: dimension.value.width / sheet.width,
    }
}

function dragMove(event: PointerEvent): void {
  const element = moving && elements.value[moving.index]
  if (!moving || !element) return
  element.x = moving.x + (event.clientX - moving.clientX) * moving.ratio
  element.y = moving.y + (event.clientY - moving.clientY) * moving.ratio
  place(element)
}

function drop(event: DragEvent): void {
  const value = event.dataTransfer?.getData('text/plain') ?? ''
  if (!value || !canEdit.value || !draft.value) return
  const [key, field] = value.split(':', 2)
  if (!(key && Object.hasOwn(printElementLabels, key))) return
  const target = (event.target as HTMLElement)?.closest('.print-design-sheet')
  if (!target) return
  const box = target.getBoundingClientRect()
  const ratio = dimension.value.width / box.width
  create(key as PrintElementType, field, {
    x: (event.clientX - box.left) * ratio - draft.value.layout.paper.margins.left,
    y: (event.clientY - box.top) * ratio - draft.value.layout.paper.margins.top,
  })
}

function transfer(event: DragEvent, type: PrintElementType, field?: string): void {
  event.dataTransfer?.setData('text/plain', field ? `${type}:${field}` : type)
}
function remove(): void {
  if (selectedIndex.value === undefined) return
  elements.value.splice(selectedIndex.value, 1)
  selectedIndex.value = undefined
}

function addTable(): void {
  if (!draft.value || !lineFields.value.length) return
  draft.value.layout.table = {
    x: 0,
    y: 24,
    rowHeight: 5,
    rowsPerPage: 5,
    repeatHeader: true,
    showTotalRow: false,
    columns: [],
  }
  for (const field of lineFields.value.filter((field) =>
    ['lines.number', 'lines.skuCode', 'lines.qty'].includes(field.id),
  ))
    addColumn(field.id)
}
function addColumn(key = lineFields.value[0]?.id): void {
  const field = lineFields.value.find((item) => item.id === key)
  if (field)
    table.value?.columns.push({
      field: field.id,
      title: field.label,
      titleEn: field.english,
      w: 28,
      total: false,
    })
}

function upload(event: Event, kind: 'companyLogo' | 'seal' | 'background'): void {
  const input = event.currentTarget as HTMLInputElement
  const file = input.files?.[0]
  const target = draft.value
  input.value = ''
  if (!file || !target) return
  if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 256 * 1024) {
    error.value = '请选择 256 KB 以内的 PNG 或 JPEG 图片。'
    return
  }
  const current = revision
  const reader = new FileReader()
  reader.onload = () => {
    if (revision !== current || draft.value !== target || typeof reader.result !== 'string') return
    if (kind === 'background') target.layout.background = reader.result
    else target.layout.images = { ...target.layout.images, [kind]: reader.result }
  }
  reader.onerror = () => {
    if (revision === current) error.value = '未能读取所选图片。'
  }
  reader.readAsDataURL(file)
}

function openPreview(): void {
  if (!draft.value) return
  const input = previewDocument.value.trim(),
    chosen = previewDocumentChoice.value
  const documentId = chosen && chosen.value === input ? chosen.id : input
  if (documentId && !/^[1-9][0-9]{0,18}$/.test(documentId)) {
    error.value = '请选择检索到的业务单据，或直接输入有效的单据编号。'
    return
  }
  const { documentType, layout } = draft.value
  void forCurrent(
    (signal) => printDesignPreview(documentType, layout, documentId || undefined, signal),
    (model) => {
      preview.value = model
      previewVisible.value = true
    },
  )
}

function fetchChoices(
  query: string,
  done: (items: Array<{ id: string; value: string }>) => void,
): void {
  const type = draft.value?.documentType
  if (!type) {
    done([])
    return
  }
  const current = revision
  void printableDocuments(type, query.trim(), scope.signal)
    .then((items) => {
      if (current === revision && draft.value?.documentType === type)
        done(items.map(({ id, documentNo }) => ({ id, value: documentNo })))
    })
    .catch((cause: unknown) => {
      if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
      done([])
    })
}

function chooseDocument(item: Record<string, unknown>): void {
  if (typeof item.id === 'string' && typeof item.value === 'string')
    previewDocumentChoice.value = { id: item.id, value: item.value }
}

function submitBatch(): void {
  if (!draft.value || !templateId.value) return
  const ids = [
    ...batchSelection.value,
    ...batchIds.value
      .trim()
      .split(/[\s,，]+/)
      .filter(Boolean),
  ]
  if (
    !ids.length ||
    ids.length > 50 ||
    ids.some((id) => !/^[1-9][0-9]{0,18}$/.test(id)) ||
    new Set(ids).size !== ids.length
  ) {
    error.value = '请输入 1 至 50 个不重复的同类单据编号。'
    return
  }
  const call = JSON.stringify([draft.value.documentType, templateId.value, ids, copies.value])
  if (call !== signature) {
    signature = call
    batchKey = newIdempotencyKey()
  }
  const type = draft.value.documentType,
    id = templateId.value
  void forCurrent(
    (signal) => createPrintPdf(type, id, ids, copies.value, batchKey, signal),
    (job) => {
      taskId.value = job.taskId
      batchVisible.value = false
      notice.value = '批量 PDF 任务已提交。'
    },
  )
}

function searchBatch(query: string): void {
  const type = draft.value?.documentType
  if (!type) return
  const current = revision,
    currentSearch = ++batchSearch
  batchSearching.value = true
  void printableDocuments(type, query.trim(), scope.signal)
    .then((found) => {
      if (current !== revision || currentSearch !== batchSearch || !batchVisible.value) return
      const selected = batchChoices.value.filter((item) => batchSelection.value.includes(item.id))
      batchChoices.value = [
        ...new Map([...selected, ...found].map((item) => [item.id, item])).values(),
      ]
    })
    .catch((cause: unknown) => {
      if (current === revision && currentSearch === batchSearch && !isCancelled(cause))
        error.value = messageOf(cause)
    })
    .finally(() => {
      if (current === revision && currentSearch === batchSearch) batchSearching.value = false
    })
}

function openBatch(): void {
  batchVisible.value = true
  batchSelection.value = []
  batchChoices.value = []
  batchIds.value = ''
  error.value = ''
  searchBatch('')
}

watch(() => session.companyId, company, { immediate: true })
watch(section, () => {
  selectedIndex.value = undefined
})
watch(
  () => draft.value?.documentType,
  () => {
    previewDocument.value = ''
    previewDocumentChoice.value = undefined
  },
)
onScopeDispose(() => {
  revision++
  scope.abort()
})
</script>

<template>
  <div class="page-heading">
    <h1>打印模板</h1>
    <el-button v-if="canManage" type="primary" :disabled="pending || !docs.length" @click="start()"
      >新建模板</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="notice" role="status">
    {{ notice }}
    <router-link v-if="taskId" :to="{ name: 'file-jobs', query: { tab: 'export', jobId: taskId } }"
      >查看后台任务</router-link
    >
  </p>
  <section class="panel template-library" aria-label="模板列表">
    <el-select
      v-model="filter"
      aria-label="单据类型筛选"
      placeholder="全部单据"
      clearable
      @change="list()"
      ><el-option v-for="doc in docs" :key="doc.id" :label="doc.label" :value="doc.id"
    /></el-select>
    <p v-if="initialized && !templates.length">目前没有自定义模板。单据仍可用系统默认模板打印。</p>
    <div class="template-buttons">
      <el-button
        v-for="row in templates"
        :key="row.id"
        :type="templateId === row.id ? 'primary' : 'default'"
        :disabled="pending"
        @click="edit(row.id)"
        >{{ row.name }} · {{ statusLabels[row.status]
        }}{{ row.isDefault ? ' · 默认' : '' }}</el-button
      >
    </div>
    <el-button v-if="cursor" text @click="list(cursor)">加载更多</el-button>
  </section>

  <section v-if="draft" class="panel template-editor" aria-label="模板编辑器">
    <div class="print-toolbar">
      <label
        >名称<el-input
          v-model="draft.name"
          aria-label="模板名称"
          maxlength="100"
          :disabled="!canEdit"
      /></label>
      <label
        >单据类型<el-select
          :model-value="draft.documentType"
          aria-label="模板单据类型"
          :disabled="!!templateId || !canEdit"
          @change="start"
          ><el-option
            v-for="doc in docs"
            :key="doc.id"
            :label="doc.label"
            :value="doc.id" /></el-select
      ></label>
      <label
        >状态<el-select v-model="draft.status" aria-label="模板状态" :disabled="!canEdit"
          ><el-option
            v-for="(value, key) in statusLabels"
            :key="key"
            :value="key"
            :label="value" /></el-select
      ></label>
      <el-checkbox v-model="draft.isDefault" :disabled="!canEdit">作为默认模板</el-checkbox>
      <el-checkbox
        v-if="session.can('print_template:tenant_manage') || draft.shared"
        v-model="draft.shared"
        :disabled="!!templateId || !canEdit"
        >企业共享</el-checkbox
      >
      <el-button
        v-if="canEdit"
        type="primary"
        :loading="pending"
        :disabled="!draft.name.trim()"
        @click="save"
        >保存模板</el-button
      >
      <el-button
        v-if="templateId && draft.status === 'active'"
        :disabled="pending"
        @click="openBatch"
        >批量输出 PDF</el-button
      >
    </div>
    <div class="print-toolbar">
      <label
        >纸型<el-select
          v-model="draft.layout.paper.kind"
          aria-label="纸型"
          :disabled="!canEdit"
          @change="fitPaper"
          ><el-option value="A4" label="A4" /><el-option value="A5" label="A5" /><el-option
            value="third"
            label="241 × 93 毫米三等分" /><el-option value="custom" label="自定义尺寸" /></el-select
      ></label>
      <label
        >方向<el-select
          v-model="draft.layout.paper.orientation"
          aria-label="纸张方向"
          :disabled="!canEdit"
          @change="fitPaper"
          ><el-option value="portrait" label="按原尺寸" /><el-option
            value="landscape"
            label="旋转 90°" /></el-select
      ></label>
      <label v-if="draft.layout.paper.kind === 'custom'"
        >宽（毫米）<el-input-number
          v-model="draft.layout.paper.width"
          @change="fitPaper"
          :min="30"
          :max="500"
          :disabled="!canEdit"
      /></label>
      <label v-if="draft.layout.paper.kind === 'custom'"
        >高（毫米）<el-input-number
          v-model="draft.layout.paper.height"
          @change="fitPaper"
          :min="30"
          :max="500"
          :disabled="!canEdit"
      /></label>
      <label
        >语言<el-select v-model="draft.layout.locale" aria-label="打印语言" :disabled="!canEdit"
          ><el-option value="zh-CN" label="简体中文" /><el-option
            value="en-US"
            label="English" /></el-select
      ></label>
      <el-checkbox v-model="snap" :disabled="!canEdit">吸附至 1 毫米</el-checkbox>
      <label
        >真实单据<el-autocomplete
          v-model="previewDocument"
          aria-label="预览单据编号"
          placeholder="搜索单号或留空用样例"
          :fetch-suggestions="fetchChoices"
          :debounce="250"
          @select="chooseDocument"
      /></label>
      <el-button :loading="pending" @click="openPreview">预览</el-button>
    </div>

    <div class="print-designer-grid">
      <aside class="element-menu" aria-label="打印元素">
        <h2>元素和字段</h2>
        <el-radio-group v-model="section"
          ><el-radio-button value="header">页头</el-radio-button
          ><el-radio-button value="footer">页尾</el-radio-button></el-radio-group
        >
        <div class="element-palette">
          <button
            v-for="[type, title] in headings"
            :key="type"
            type="button"
            :disabled="!canEdit"
            :draggable="canEdit"
            @dragstart="transfer($event, type)"
            @click="create(type)"
          >
            {{ title }}
          </button>
        </div>
        <el-button v-if="!table && lineFields.length" :disabled="!canEdit" @click="addTable"
          >添加明细表</el-button
        >
        <ul class="field-palette">
          <li v-for="field in docFields" :key="field.id">
            <button
              type="button"
              :disabled="!canEdit"
              :draggable="canEdit"
              @dragstart="transfer($event, 'field', field.id)"
              @click="create('field', field.id)"
            >
              {{ field.label }}
            </button>
          </li>
        </ul>
      </aside>
      <div class="print-stage" @dragover.prevent @drop.prevent="drop">
        <div class="ruler" aria-label="纸张标尺（毫米）" :style="{ width: `${dimension.width}mm` }">
          <span v-for="i in Math.floor(dimension.width / 10)" :key="i">{{ (i - 1) * 10 }}</span>
        </div>
        <PrintCanvas :model="authoringModel" editing>
          <div v-if="selected" class="vertical-guide" :style="{ left: tile(selected).left }" />
          <div v-if="selected" class="horizontal-guide" :style="{ top: tile(selected).top }" />
          <button
            v-for="(element, index) in elements"
            :key="index"
            type="button"
            :style="tile(element)"
            :aria-pressed="selectedIndex === index"
            :class="['element-tile', { selected: selectedIndex === index }]"
            @click="selectedIndex = index"
            @pointerdown="beginMove($event, element, index)"
            @pointermove="dragMove"
            @lostpointercapture="moving = undefined"
          >
            {{ elementName(element) }}
          </button>
          <div
            v-if="table"
            class="table-tile"
            :style="{
              left: `${table.x + draft.layout.paper.margins.left}mm`,
              top: `${table.y + draft.layout.paper.margins.top}mm`,
              width: `${table.columns.reduce((sum, col) => sum + col.w, 0)}mm`,
              height: `${(table.rowsPerPage + 1 + (table.showTotalRow ? 1 : 0)) * table.rowHeight}mm`,
            }"
          >
            明细表
          </div>
        </PrintCanvas>
      </div>
      <aside class="print-properties" aria-label="模板属性">
        <h2>属性</h2>
        <details open>
          <summary>页边距（毫米）</summary>
          <div class="dimension-fields">
            <label
              v-for="(title, key) in { top: '上', right: '右', bottom: '下', left: '左' }"
              :key="key"
              >{{ title
              }}<el-input-number
                v-model="draft.layout.paper.margins[key]"
                :min="0"
                :max="50"
                :disabled="!canEdit"
            /></label>
          </div>
        </details>
        <details open>
          <summary>选中元素</summary>
          <p v-if="!selected">在纸张上选中元素以编辑。</p>
          <template v-else>
            <div class="dimension-fields">
              <label
                v-for="(title, key) in { x: '横坐标', y: '纵坐标', w: '宽', h: '高' }"
                :key="key"
                >{{ title
                }}<el-input-number
                  v-model="selected[key]"
                  :min="key === 'w' || key === 'h' ? 0.5 : 0"
                  :max="500"
                  :step="0.5"
                  :disabled="!canEdit"
                  @change="place(selected!)"
              /></label>
            </div>
            <template v-if="selected.type === 'text'"
              ><label>中文文字<el-input v-model="selected.text" :disabled="!canEdit" /></label
              ><label>英文文字<el-input v-model="selected.textEn" :disabled="!canEdit" /></label
            ></template>
            <template v-if="selected.type === 'field'"
              ><label>中文标签<el-input v-model="selected.label" :disabled="!canEdit" /></label
              ><label>英文标签<el-input v-model="selected.labelEn" :disabled="!canEdit" /></label
              ><label
                >显示格式<el-select
                  v-model="selected.format"
                  clearable
                  placeholder="字段默认格式"
                  :disabled="!canEdit"
                  ><el-option
                    v-for="format in formats"
                    :key="format.value"
                    :label="format.title"
                    :value="format.value" /></el-select></label
            ></template>
            <label v-if="['field', 'barcode', 'qrcode'].includes(selected.type)"
              >数据字段<el-select v-model="selected.field" :disabled="!canEdit"
                ><el-option
                  v-for="field in docFields"
                  :key="field.id"
                  :label="field.label"
                  :value="field.id" /></el-select
            ></label>
            <label v-if="selected.type === 'image'"
              >图片来源<el-select v-model="selected.source" :disabled="!canEdit"
                ><el-option value="companyLogo" label="公司标识" /><el-option
                  value="seal"
                  label="签章图片" /></el-select
            ></label>
            <label v-if="selected.type === 'barcode'"
              >条码类型<el-select v-model="selected.symbology" :disabled="!canEdit"
                ><el-option value="code128" label="Code 128" /><el-option
                  value="qr"
                  label="二维码" /></el-select
            ></label>
            <template v-if="['text', 'field', 'pageNumber'].includes(selected.type)"
              ><label
                >字号<el-input-number
                  v-model="selected.style.fontSize"
                  :min="6"
                  :max="40"
                  :disabled="!canEdit" /></label
              ><label
                >对齐<el-select v-model="selected.style.align" :disabled="!canEdit"
                  ><el-option value="left" label="左对齐" /><el-option
                    value="center"
                    label="居中" /><el-option value="right" label="右对齐" /></el-select></label
              ><el-checkbox v-model="selected.style.bold" :disabled="!canEdit"
                >加粗</el-checkbox
              ></template
            >
            <el-button v-if="canEdit" text type="danger" @click="remove">移除元素</el-button>
          </template>
        </details>
        <details v-if="table" open>
          <summary>明细表</summary>
          <div class="dimension-fields">
            <label
              v-for="(title, key) in {
                x: '横坐标',
                y: '纵坐标',
                rowHeight: '行高',
                rowsPerPage: '每页行数',
              }"
              :key="key"
              >{{ title
              }}<el-input-number
                v-model="table[key]"
                :min="key === 'rowHeight' ? 3 : key === 'rowsPerPage' ? 1 : 0"
                :max="key === 'rowsPerPage' ? 80 : key === 'rowHeight' ? 30 : 500"
                :disabled="!canEdit"
            /></label>
          </div>
          <el-checkbox v-model="table.repeatHeader" :disabled="!canEdit">重复表头</el-checkbox
          ><el-checkbox v-model="table.showTotalRow" :disabled="!canEdit">末页汇总行</el-checkbox>
          <fieldset v-for="(col, index) in table.columns" :key="index">
            <legend>第 {{ index + 1 }} 列</legend>
            <el-select
              v-model="col.field"
              :aria-label="`第 ${index + 1} 列数据字段`"
              :disabled="!canEdit"
              ><el-option
                v-for="field in lineFields"
                :key="field.id"
                :value="field.id"
                :label="field.label"
            /></el-select>
            <label>列名<el-input v-model="col.title" :disabled="!canEdit" /></label
            ><label>英文列名<el-input v-model="col.titleEn" :disabled="!canEdit" /></label>
            <label
              >列宽（毫米）<el-input-number
                v-model="col.w"
                :min="5"
                :max="200"
                :disabled="!canEdit"
            /></label>
            <el-checkbox v-model="col.total" :disabled="!canEdit">汇总此列</el-checkbox
            ><el-button v-if="canEdit" text @click="table.columns.splice(index, 1)"
              >移除此列</el-button
            >
          </fieldset>
          <el-button v-if="canEdit" text @click="addColumn()">添加列</el-button
          ><el-button v-if="canEdit" text type="danger" @click="draft.layout.table = null"
            >移除明细表</el-button
          >
        </details>
        <details v-if="canEdit">
          <summary>套打和图片</summary>
          <label
            >公司标识<input
              type="file"
              accept="image/png,image/jpeg"
              @change="upload($event, 'companyLogo')" /></label
          ><el-button
            v-if="draft.layout.images?.companyLogo"
            text
            @click="delete draft.layout.images.companyLogo"
            >移除标识</el-button
          >
          <label
            >签章图片<input
              type="file"
              accept="image/png,image/jpeg"
              @change="upload($event, 'seal')" /></label
          ><el-button v-if="draft.layout.images?.seal" text @click="delete draft.layout.images.seal"
            >移除签章</el-button
          >
          <label
            >套打对位背景<input
              type="file"
              accept="image/png,image/jpeg"
              @change="upload($event, 'background')" /></label
          ><el-button v-if="draft.layout.background" text @click="draft.layout.background = null"
            >移除背景</el-button
          >
        </details>
      </aside>
    </div>
  </section>
  <el-dialog v-model="previewVisible" title="模板预览" width="min(1000px, 94vw)"
    ><div v-if="preview" class="print-stage"><PrintCanvas :model="preview" /></div
  ></el-dialog>
  <el-dialog v-model="batchVisible" title="批量输出 PDF" width="min(500px, 94vw)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <label
      >选择同类单据
      <el-select
        v-model="batchSelection"
        multiple
        filterable
        remote
        reserve-keyword
        :remote-method="searchBatch"
        :loading="batchSearching"
        :multiple-limit="50"
        aria-label="选择批量打印单据"
        placeholder="搜索业务单号"
        style="width: 100%"
      >
        <el-option
          v-for="option in batchChoices"
          :key="option.id"
          :label="option.documentNo"
          :value="option.id"
        />
      </el-select>
    </label>
    <label
      >同类单据编号<el-input
        v-model="batchIds"
        type="textarea"
        :rows="4"
        aria-label="批量打印单据编号"
        placeholder="用空格、逗号或换行分隔"
    /></label>
    <label
      >每张份数<el-input-number v-model="copies" :min="1" :max="10" aria-label="每张打印份数"
    /></label>
    <template #footer
      ><el-button @click="batchVisible = false">取消</el-button
      ><el-button type="primary" :loading="pending" @click="submitBatch"
        >提交后台打印</el-button
      ></template
    >
  </el-dialog>
</template>

<style scoped>
.template-library,
.template-editor {
  padding: 16px;
  margin: 12px 0;
}
.template-library > .el-select {
  max-width: 240px;
}
.template-buttons,
.element-palette,
.print-toolbar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 8px 0;
}
.template-buttons .el-button,
.print-toolbar .el-button {
  margin-left: 0;
}
.print-toolbar {
  border-bottom: 1px solid #dee5ef;
  align-items: end;
  padding-bottom: 8px;
}
.print-toolbar label,
.print-properties label {
  display: grid;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.print-toolbar label {
  width: 140px;
}
.print-designer-grid {
  display: grid;
  grid-template-columns: 170px minmax(240px, 1fr) 255px;
  gap: 12px;
}
.print-designer-grid h2 {
  font-size: 15px;
  margin: 0 0 12px;
}
.print-stage {
  min-height: 240px;
  max-height: 580px;
  overflow: auto;
  background: #e9eef4;
  padding: 8px 12px;
}
.print-stage :deep(.print-design-sheet) {
  margin: 0 0 14px;
}
.ruler {
  display: flex;
  justify-content: space-evenly;
  box-sizing: border-box;
  background: #f6f8fb;
  color: #40566e;
  font-size: 10px;
  min-height: 17px;
  align-items: end;
}
.ruler span {
  flex: 1;
  border-left: 1px solid #94a4b6;
  padding-left: 2px;
}
.element-palette button,
.field-palette button {
  background: #f5f7fa;
  border: 1px solid #b8c5d2;
  border-radius: 4px;
  color: #263952;
  padding: 4px 6px;
  cursor: grab;
}
.element-palette button:disabled,
.field-palette button:disabled {
  cursor: default;
  opacity: 0.65;
}
.field-palette {
  padding: 0;
  list-style: none;
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.element-tile,
.table-tile {
  box-sizing: border-box;
  position: absolute;
  font-size: 12px;
  background: #eefffddd;
  border: 1px dashed #52908d;
  color: #244c4d;
  overflow: hidden;
  text-align: left;
}
.element-tile {
  touch-action: none;
  cursor: move;
}
.element-tile.selected {
  border: 1px solid #1f6ace;
  background: #cce1ff;
  color: #183965;
  z-index: 2;
}
.table-tile {
  background: #fff9edc9;
  border-color: #ab7736;
  pointer-events: none;
}
.vertical-guide,
.horizontal-guide {
  position: absolute;
  z-index: 1;
  pointer-events: none;
}
.vertical-guide {
  top: 0;
  bottom: 0;
  border-left: 1px solid #2679d4;
}
.horizontal-guide {
  left: 0;
  right: 0;
  border-top: 1px solid #2679d4;
}
.print-properties details {
  border-bottom: 1px solid #e2e9ee;
  margin-bottom: 8px;
  padding-bottom: 8px;
}
.print-properties summary {
  cursor: pointer;
  font-weight: 600;
  margin: 5px 0 9px;
}
.print-properties label {
  margin-bottom: 6px;
}
.print-properties :deep(.el-input-number) {
  width: 100%;
}
.dimension-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}
.print-properties fieldset {
  border: 1px solid #e2e8f2;
  margin: 6px 0;
  padding: 6px;
  min-width: 0;
}
.print-properties input[type='file'] {
  max-width: 100%;
}
@media (max-width: 1050px) {
  .print-designer-grid {
    grid-template-columns: 125px minmax(0, 1fr);
  }
  .print-properties {
    grid-column: 1/-1;
  }
}
@media (max-width: 620px) {
  .print-designer-grid {
    display: block;
  }
  .print-stage {
    margin: 12px 0;
  }
  .print-toolbar label {
    width: 125px;
  }
}
</style>
