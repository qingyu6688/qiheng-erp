<script setup lang="ts">
import { computed, inject, onMounted, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { aiRead, aiWrite } from '../../api/ai'
import { aiDepartmentChoices, aiRoleChoices, type AiChoice } from '../../api/ai-choices'
import { isCancelled, messageOf } from '../../api/http'
import { formatDateTimeSeconds } from '../../utils/format'
import { useSessionStore } from '../../stores/session'
import type { AiDocument, AiLibrary, AiLibraryInput, AiRecallHit, AiSegment } from '../../types/ai'
import '../../styles/ai-workspace.css'

const session = useSessionStore()
const readOnlyPreview = inject('erp.readOnlyPreview', false)
const router = useRouter()
const editable = computed(() => session.can('ai_admin:manage'))
const libraryId = ref('')
const libraries = ref<AiLibrary[]>([])
const selectedLibrary = computed(() => libraries.value.find((item) => item.id === libraryId.value))
const documents = ref<AiDocument[]>([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
let active = true

const editLibrary = ref(false)
const editingId = ref('')
const libraryForm = ref<AiLibraryInput>(blankLibrary())
const roles = ref<AiChoice[]>([])
const departments = ref<AiChoice[]>([])
const targetChoices = computed(() =>
  libraryForm.value.visibility === 'roles' ? roles.value : departments.value,
)

const uploadOpen = ref(false)
const uploadTarget = ref<AiDocument>()
const uploadTitle = ref('')
const uploadFile = ref<File>()
const segmentsDoc = ref<AiDocument>()
const segments = ref<AiSegment[]>([])
const segmentAction = ref<'split' | 'merge' | 'disable'>('split')
const segmentId = ref('')
const splitOffset = ref<number>()
const selectedSegment = computed(() => segments.value.find((item) => item.id === segmentId.value))

const recallQuery = ref('')
const recallAttempted = ref(false)
const recallHits = ref<AiRecallHit[]>([])
const visibilityLabels = { all: '企业成员可读', roles: '指定角色', departments: '指定部门' }
const statusLabels: Record<string, string> = {
  active: '启用',
  uploaded: '待解析',
  parsing: '解析中',
  indexed: '已入库',
  failed: '解析失败',
  disabled: '已停用',
}

function blankLibrary(): AiLibraryInput {
  return { name: '', description: '', visibility: 'all', targetIds: [], status: 'active' }
}

async function perform(action: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) busy.value = false
  }
}

async function loadDocuments(): Promise<void> {
  const id = libraryId.value
  const result = id ? await aiRead<AiDocument[]>('/admin/documents', { libraryId: id }) : []
  if (active && id === libraryId.value) documents.value = result
}

function closeSegments(): void {
  segmentsDoc.value = undefined
  segments.value = []
  segmentId.value = ''
}

function clearDetail(): void {
  closeSegments()
  recallHits.value = []
  recallAttempted.value = false
}

async function load(): Promise<void> {
  clearDetail()
  const available = await aiRead<AiLibrary[]>('/admin/libraries')
  if (!active) return
  libraries.value = available
  if (!available.some((item) => item.id === libraryId.value)) {
    libraryId.value = available[0]?.id ?? ''
    clearDetail()
  }
  await loadDocuments()
}

async function selectLibrary(value: string): Promise<void> {
  clearDetail()
  libraryId.value = value
  documents.value = []
  await perform(loadDocuments)
}

async function openLibrary(library?: AiLibrary): Promise<void> {
  editingId.value = library?.id ?? ''
  libraryForm.value = library ? { ...library, targetIds: [...library.targetIds] } : blankLibrary()
  editLibrary.value = true
  const [roleResult, departmentResult] = await Promise.allSettled([
    session.can('role:view') ? aiRoleChoices() : Promise.resolve([]),
    session.can('org_department:view') ? aiDepartmentChoices() : Promise.resolve([]),
  ])
  if (!active) return
  if (roleResult.status === 'fulfilled') roles.value = roleResult.value
  else if (!isCancelled(roleResult.reason)) error.value = messageOf(roleResult.reason)
  if (departmentResult.status === 'fulfilled') departments.value = departmentResult.value
  else if (!isCancelled(departmentResult.reason)) error.value = messageOf(departmentResult.reason)
}

async function saveLibrary(): Promise<void> {
  const { name, description, status, visibility, targetIds } = libraryForm.value
  await perform(async () => {
    await aiWrite(
      editingId.value
        ? `/admin/libraries/${encodeURIComponent(editingId.value)}`
        : '/admin/libraries',
      { name: name.trim(), description: description.trim(), status, visibility, targetIds },
      editingId.value ? 'PUT' : 'POST',
    )
    if (!active) return
    editLibrary.value = false
    clearDetail()
    await load()
    if (active) notice.value = '已保存知识库。'
  })
}

function openUpload(document?: AiDocument): void {
  uploadTarget.value = document
  uploadFile.value = undefined
  uploadTitle.value = document?.title ?? ''
  uploadOpen.value = true
}

function chooseFile(event: Event): void {
  if (event.target instanceof HTMLInputElement) uploadFile.value = event.target.files?.[0]
}

async function upload(): Promise<void> {
  const file = uploadFile.value
  if (!file) return
  await perform(async () => {
    if (file.size > 20 * 1024 * 1024 || !file.size) {
      error.value = '文件必须大于 0 且不超过 20 MB。'
      return
    }
    const data = new FormData()
    data.set('libraryId', libraryId.value)
    data.set('file', file)
    if (uploadTitle.value.trim()) data.set('title', uploadTitle.value.trim())
    if (uploadTarget.value) data.set('documentId', uploadTarget.value.id)
    await aiWrite('/admin/documents', data, 'POST', 120_000)
    if (!active) return
    uploadOpen.value = false
    uploadFile.value = undefined
    clearDetail()
    await loadDocuments()
    if (active) notice.value = '已提交解析，可刷新列表查看入库状态。'
  })
}

async function command(document: AiDocument, action: 'retry' | 'disable'): Promise<void> {
  if (
    action === 'disable' &&
    !window.confirm(`停用「${document.title}」会使相关引用失效。确定停用？`)
  )
    return
  await perform(async () => {
    await aiWrite(`/admin/documents/${encodeURIComponent(document.id)}/${action}`, {})
    if (!active) return
    clearDetail()
    await loadDocuments()
  })
}

async function readSegments(document: AiDocument): Promise<void> {
  const result = await aiRead<AiSegment[]>(
    `/admin/documents/${encodeURIComponent(document.id)}/segments`,
  )
  if (!active) return
  segmentsDoc.value = document
  segments.value = result
  segmentId.value = result.find((item) => item.status === 'indexed')?.id ?? result[0]?.id ?? ''
  splitOffset.value = undefined
}

async function openSegments(document: AiDocument): Promise<void> {
  if (readOnlyPreview) {
    await router.push('/ai/knowledge/' + encodeURIComponent(document.id))
    return
  }
  await perform(async () => {
    segmentsDoc.value = undefined
    segments.value = []
    await readSegments(document)
  })
}

const nextSegment = computed(() => {
  const current = selectedSegment.value
  if (!current) return undefined
  const indexed = segments.value.filter((item) => item.status === 'indexed')
  const next = indexed[indexed.findIndex((item) => item.id === current.id) + 1]
  return next?.heading === current.heading ? next : undefined
})
const canAdjust = computed(() => {
  if (selectedSegment.value?.status !== 'indexed') return false
  if (segmentAction.value === 'disable') return true
  if (segmentAction.value === 'merge') return Boolean(nextSegment.value)
  const count = selectedSegment.value.charCount
  return typeof splitOffset.value === 'number' && splitOffset.value > 0 && splitOffset.value < count
})

async function adjust(): Promise<void> {
  const document = segmentsDoc.value
  const current = selectedSegment.value
  const operation = segmentAction.value
  if (
    !document ||
    !current ||
    !canAdjust.value ||
    !window.confirm('确定调整当前片段？关联的引用和 Wiki 页面可能需要重新审核。')
  )
    return
  await perform(async () => {
    await aiWrite(`/admin/documents/${encodeURIComponent(document.id)}/segments/adjust`, {
      operation,
      segmentId: current.id,
      nextSegmentId: operation === 'merge' ? nextSegment.value?.id : undefined,
      offset: operation === 'split' ? splitOffset.value : undefined,
    })
    if (!active) return
    recallHits.value = []
    recallAttempted.value = false
    await readSegments(document)
    if (active) notice.value = '已更新分段。'
  })
}

async function recall(): Promise<void> {
  if (!libraryId.value || !recallQuery.value.trim()) return
  await perform(async () => {
    recallHits.value = []
    recallAttempted.value = false
    const result = await aiWrite<AiRecallHit[]>(
      '/admin/libraries/search',
      {
        query: recallQuery.value.trim(),
        libraryIds: [libraryId.value],
      },
      'POST',
      60_000,
    )
    if (!active) return
    recallHits.value = result
    recallAttempted.value = true
  })
}

onMounted(() => perform(load))
onScopeDispose(() => {
  active = false
})
</script>

<template>
  <div class="ai-page">
    <div class="page-heading">
      <div>
        <h1>{{ readOnlyPreview ? '知识库' : '知识库管理' }}</h1>
        <p>{{ readOnlyPreview ? '浏览示例资料和分段原文；所有资料均为虚构示例。' : '指定部门时，按成员在当前公司的任职判断阅读权限。' }}</p>
      </div>
      <div class="ai-actions">
        <el-button :disabled="busy" @click="perform(load)">刷新</el-button>
        <el-button v-if="editable" type="primary" :disabled="busy" @click="openLibrary()"
          >新建知识库</el-button
        >
      </div>
    </div>
    <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" class="ai-error" :title="notice" type="success" :closable="false" />

    <section class="panel ai-section" :aria-busy="busy">
      <div class="page-heading">
        <h2>资料文档</h2>
        <div class="ai-actions">
          <el-select
            :model-value="libraryId"
            placeholder="选择知识库"
            aria-label="选择知识库"
            :disabled="busy"
            style="width: min(240px, 70vw)"
            @change="selectLibrary"
          >
            <el-option
              v-for="item in libraries"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <template v-if="editable && selectedLibrary">
            <el-button :disabled="busy" @click="openLibrary(selectedLibrary)">编辑知识库</el-button>
            <el-button
              type="primary"
              :disabled="busy || selectedLibrary.status !== 'active'"
              @click="openUpload()"
              >上传资料</el-button
            >
          </template>
        </div>
      </div>
      <template v-if="selectedLibrary">
        <p>
          {{ visibilityLabels[selectedLibrary.visibility] }} ·
          {{ statusLabels[selectedLibrary.status] }}
        </p>
        <p v-if="selectedLibrary.description" class="ai-wrap">{{ selectedLibrary.description }}</p>
        <el-table :data="documents" :empty-text="busy ? '正在读取…' : '暂无文档'">
          <el-table-column prop="title" label="资料标题" min-width="180" />
          <el-table-column prop="versionNo" label="版本" width="80" />
          <el-table-column label="入库状态" min-width="120">
            <template #default="{ row }"
              ><el-tag
                size="small"
                :type="
                  row.status === 'failed' ? 'danger' : row.status === 'indexed' ? 'success' : 'info'
                "
                >{{ statusLabels[row.status] ?? row.status }}</el-tag
              ></template
            >
          </el-table-column>
          <el-table-column prop="error" label="失败说明" show-overflow-tooltip min-width="160" />
          <el-table-column label="更新时间" min-width="155"
            ><template #default="{ row }">{{
              formatDateTimeSeconds(row.updatedAt)
            }}</template></el-table-column
          >
          <el-table-column label="操作" min-width="230">
            <template #default="{ row }">
              <div class="ai-actions">
                <el-button
                  v-if="row.status === 'indexed'"
                  link
                  type="primary"
                  :disabled="busy"
                  @click="openSegments(row)"
                  >预览分段</el-button
                >
                <template v-if="editable">
                  <el-button
                    v-if="row.status === 'failed'"
                    link
                    type="primary"
                    :disabled="busy"
                    @click="command(row, 'retry')"
                    >重试</el-button
                  >
                  <el-button
                    v-if="['indexed', 'failed', 'disabled'].includes(row.status)"
                    link
                    :disabled="busy"
                    @click="openUpload(row)"
                    >上传新版本</el-button
                  >
                  <el-button
                    v-if="row.status === 'indexed'"
                    link
                    type="danger"
                    :disabled="busy"
                    @click="command(row, 'disable')"
                    >停用</el-button
                  >
                </template>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <p v-else class="ai-empty">{{ busy ? '正在读取知识库…' : '暂无知识库。' }}</p>
    </section>

    <section v-if="segmentsDoc" class="panel ai-section">
      <div class="page-heading">
        <h2>{{ segmentsDoc.title }} · 分段</h2>
        <el-button :disabled="busy" @click="closeSegments">关闭预览</el-button>
      </div>
      <el-select
        v-model="segmentId"
        aria-label="预览片段"
        :disabled="busy"
        @change="splitOffset = undefined"
      >
        <el-option
          v-for="item in segments"
          :key="item.id"
          :label="`第 ${item.seq} 段${item.heading ? ' · ' + item.heading : ''} · ${statusLabels[item.status] ?? item.status}`"
          :value="item.id"
        />
      </el-select>
      <template v-if="selectedSegment">
        <p>
          片段编号 <code>{{ selectedSegment.id }}</code>
        </p>
        <p v-if="selectedSegment.pageFrom">
          第 {{ selectedSegment.pageFrom }} 页<span
            v-if="selectedSegment.pageTo && selectedSegment.pageTo !== selectedSegment.pageFrom"
            >至第 {{ selectedSegment.pageTo }} 页</span
          >
        </p>
        <pre class="ai-text">{{ selectedSegment.content }}</pre>
        <form
          v-if="editable && selectedSegment.status === 'indexed'"
          class="ai-actions"
          @submit.prevent="adjust"
        >
          <el-select v-model="segmentAction" aria-label="片段调整方式" style="width: 148px">
            <el-option label="拆分片段" value="split" />
            <el-option label="与下一段合并" value="merge" />
            <el-option label="停用片段" value="disable" />
          </el-select>
          <label v-if="segmentAction === 'split'"
            >切分位置（字符数）
            <el-input-number
              v-model="splitOffset"
              :min="1"
              :max="Math.max(1, selectedSegment.charCount - 1)"
              :precision="0"
              aria-label="切分位置"
          /></label>
          <el-button native-type="submit" :disabled="busy || !canAdjust">确认调整</el-button>
        </form>
      </template>
    </section>

    <section v-if="selectedLibrary && !readOnlyPreview" class="panel ai-section">
      <h2>召回测试</h2>
      <form class="ai-search" @submit.prevent="recall">
        <el-input
          v-model="recallQuery"
          aria-label="召回测试问题"
          placeholder="输入问题，查看当前知识库匹配到的片段"
          maxlength="1000"
        />
        <el-button type="primary" native-type="submit" :disabled="busy || !recallQuery.trim()"
          >测试召回</el-button
        >
      </form>
      <ul v-if="recallHits.length" class="ai-list">
        <li v-for="hit in recallHits" :key="hit.evidence.segmentId">
          <h3 class="ai-wrap">
            {{ hit.evidence.title }} {{ hit.evidence.heading ? '· ' + hit.evidence.heading : '' }}
          </h3>
          <p>分数 {{ hit.score }} · {{ hit.selected ? '已选入上下文' : '未选入上下文' }}</p>
          <pre class="ai-text">{{ hit.evidence.content }}</pre>
        </li>
      </ul>
      <p v-else-if="recallAttempted" class="ai-empty">没有匹配到当前账号可阅读的片段。</p>
    </section>

    <el-dialog
      v-model="editLibrary"
      :title="editingId ? '编辑知识库' : '新建知识库'"
      width="min(540px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveLibrary">
        <el-form-item label="知识库名称"
          ><el-input v-model="libraryForm.name" maxlength="120"
        /></el-form-item>
        <el-form-item label="说明"
          ><el-input v-model="libraryForm.description" type="textarea" maxlength="500"
        /></el-form-item>
        <el-form-item label="阅读范围">
          <el-select v-model="libraryForm.visibility" @change="libraryForm.targetIds = []">
            <el-option
              v-for="(label, value) in visibilityLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="libraryForm.visibility !== 'all'"
          :label="libraryForm.visibility === 'roles' ? '允许阅读的角色' : '允许阅读的部门'"
        >
          <el-select
            v-model="libraryForm.targetIds"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择条目或输入编号后回车"
          >
            <el-option
              v-for="item in targetChoices"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态"
          ><el-select v-model="libraryForm.status"
            ><el-option label="启用" value="active" /><el-option
              label="停用"
              value="disabled" /></el-select
        ></el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="
            !libraryForm.name.trim() ||
            (libraryForm.visibility !== 'all' && !libraryForm.targetIds.length)
          "
          >保存</el-button
        >
        <el-button :disabled="busy" @click="editLibrary = false">取消</el-button>
      </el-form>
    </el-dialog>

    <el-dialog
      v-model="uploadOpen"
      destroy-on-close
      :title="uploadTarget ? '上传文档新版本' : '上传资料'"
      width="min(540px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="upload">
        <el-form-item label="文档标题"
          ><el-input v-model="uploadTitle" maxlength="180" placeholder="留空时使用文件名"
        /></el-form-item>
        <el-form-item label="资料文件（不超过 20 MB）"
          ><input type="file" aria-label="资料文件" @change="chooseFile"
        /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!uploadFile"
          >提交解析</el-button
        >
        <el-button :disabled="busy" @click="uploadOpen = false">取消</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>
