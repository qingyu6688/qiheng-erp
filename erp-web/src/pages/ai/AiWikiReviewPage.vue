<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { aiRead, aiWrite } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import AiMarkdown from '../../components/AiMarkdown.vue'
import AiSources from '../../components/AiSources.vue'
import type { AiWikiLint, AiWikiReview } from '../../types/ai'
import '../../styles/ai-workspace.css'

const session = useSessionStore()
const items = ref<AiWikiReview[]>([])
const issues = ref<AiWikiLint[]>([])
const selected = ref<AiWikiReview>()
const content = ref('')
const editing = ref(false)
const note = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const draftOpen = ref(false)
const draftPageId = ref('')
const draftTitle = ref('')
const draftSources = ref<string[]>([])
const enriching = ref(false)
const enrichmentStatus = ref('')
let active = true

const statusLabels: Record<string, string> = {
  draft: '草稿',
  pending_review: '待审核',
  published: '已发布',
  archived: '已归档',
}
const issueLabels: Record<string, string> = {
  contradiction: '内容冲突',
  broken_link: '链接失效',
  stale_source: '来源失效',
  orphan: '缺少入链',
  inactive: '长期无人访问',
  open: '待处理',
  resolved: '已处理',
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

async function load(): Promise<void> {
  clearReview()
  const [reviews, lint] = await Promise.all([
    aiRead<AiWikiReview[]>('/admin/wiki/review'),
    aiRead<AiWikiLint[]>('/admin/wiki/lint'),
  ])
  if (!active) return
  items.value = reviews
  issues.value = lint
}

function clearReview(): void {
  selected.value = undefined
  content.value = ''
  note.value = ''
  editing.value = false
}

function showReview(review: AiWikiReview): void {
  clearReview()
  selected.value = review
  content.value = review.pendingRevision?.content ?? ''
}

async function select(id: string): Promise<void> {
  await perform(async () => {
    clearReview()
    const review = await aiRead<AiWikiReview>(`/admin/wiki/${encodeURIComponent(id)}/review`)
    if (!active) return
    showReview(review)
  })
}

async function decide(action: 'publish' | 'reject' | 'archive'): Promise<void> {
  const review = selected.value
  if (!review) return
  const actionLabel = { publish: '发布', reject: '驳回', archive: '归档' }[action]
  if (!window.confirm(`确定${actionLabel}「${review.title}」？`)) return
  await perform(async () => {
    await aiWrite(`/admin/wiki/${encodeURIComponent(review.id)}/${action}`, {
      revisionId: review.pendingRevision?.id,
      content:
        action === 'publish' && content.value !== review.pendingRevision?.content
          ? content.value.trim()
          : undefined,
      changeNote: note.value.trim() || undefined,
    })
    if (!active) return
    clearReview()
    await load()
    if (active) notice.value = `已${actionLabel}页面。`
  })
}

async function runTask(task: 'lint' | 'seed'): Promise<void> {
  await perform(async () => {
    await aiWrite(`/admin/wiki/${task}`, {})
    if (!active) return
    await load()
    if (active)
      notice.value = task === 'lint' ? '体检已完成。' : '已提交初始资料导入，请刷新查看待审稿。'
  })
}

async function regenerate(): Promise<void> {
  const review = selected.value
  if (!review?.pendingRevision || !window.confirm('确定用模型重新生成待审稿？')) return
  await perform(async () => {
    const path = `/admin/wiki/${encodeURIComponent(review.id)}/generate`
    const updated = await aiWrite<AiWikiReview>(
      path,
      { revisionId: review.pendingRevision?.id },
      'POST',
      120_000,
    )
    if (!active) return
    showReview(updated)
    notice.value = '已更新待审稿。'
  })
}

function openDraft(page?: AiWikiReview): void {
  draftPageId.value = page?.id ?? ''
  draftTitle.value = page?.title ?? ''
  draftSources.value = [
    ...(page?.pendingRevision?.sourceIds ?? page?.currentRevision?.sourceIds ?? []),
  ]
  error.value = ''
  draftOpen.value = true
}

async function createDraft(): Promise<void> {
  if (!draftTitle.value.trim() || !draftSources.value.length) return
  await perform(async () => {
    const result = await aiWrite<AiWikiReview>('/admin/wiki/drafts', {
      title: draftTitle.value.trim(),
      sourceIds: draftSources.value,
      pageId: draftPageId.value || undefined,
    })
    if (!active) return
    draftOpen.value = false
    await load()
    if (!active) return
    showReview(result)
    notice.value = '已创建待审稿。'
  })
}

async function enrichNext(manual = false): Promise<void> {
  if (!active || enriching.value) return
  enriching.value = true
  enrichmentStatus.value = ''
  try {
    const result = await aiWrite<{
      status: 'generated' | 'idle' | 'unavailable'
      review?: AiWikiReview
    }>('/admin/wiki/enrich-next', {}, 'POST', 120_000)
    if (!active) return
    const review = result.review
    if (result.status === 'generated' && review) {
      items.value = [review, ...items.value.filter((item) => item.id !== review.id)]
      enrichmentStatus.value = `「${review.title}」已生成新稿。`
      if (selected.value?.id === review.id) enrichmentStatus.value += '请重新打开稿件核对。'
    } else if (manual) {
      enrichmentStatus.value =
        result.status === 'idle' ? '暂无可处理的资料。' : '模型暂未就绪，可以先人工审核待审稿。'
    }
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) enrichmentStatus.value = messageOf(cause)
  } finally {
    if (active) enriching.value = false
  }
}

onMounted(async () => {
  await perform(load)
  if (!error.value) void enrichNext()
})
onScopeDispose(() => {
  active = false
})
</script>

<template>
  <div class="ai-page">
    <div class="page-heading">
      <div>
        <h1>Wiki 审核</h1>
        <p>核对新稿的内容和引用来源，发布后供有权限的成员阅读。</p>
      </div>
      <div class="ai-actions">
        <el-button :disabled="busy" @click="perform(load)">刷新</el-button>
        <el-button v-if="session.can('ai_admin:manage')" :disabled="busy" @click="runTask('seed')"
          >导入初始资料</el-button
        >
        <el-button :disabled="busy" @click="runTask('lint')">运行体检</el-button>
        <el-button :loading="enriching" :disabled="busy" @click="enrichNext(true)"
          >处理下一份资料</el-button
        >
        <el-button type="primary" :disabled="busy" @click="openDraft()">新建 Wiki 草稿</el-button>
      </div>
    </div>
    <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" class="ai-error" :title="notice" type="success" :closable="false" />
    <el-alert
      v-if="enrichmentStatus"
      class="ai-error"
      :title="enrichmentStatus"
      type="info"
      :closable="false"
    />
    <section class="panel ai-section" :aria-busy="busy">
      <h2>页面列表</h2>
      <el-table :data="items" :empty-text="busy ? '正在读取…' : '暂无可审核的页面'">
        <el-table-column prop="title" label="页面标题" min-width="210" />
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">{{
            row.stale ? '来源变更，待复审' : (statusLabels[row.status] ?? row.status)
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="busy" @click="select(row.id)"
              >查看稿件</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </section>
    <section v-if="selected" class="panel ai-section">
      <div class="page-heading">
        <h2>{{ selected.title }}</h2>
        <el-button :disabled="busy" @click="clearReview">关闭详情</el-button>
      </div>
      <div class="ai-grid wiki-comparison">
        <div class="wiki-revision">
          <h3>当前发布稿</h3>
          <AiMarkdown v-if="selected.currentRevision" :content="selected.currentRevision.content" />
          <p v-else class="ai-empty">尚未发布。</p>
        </div>
        <div class="wiki-revision">
          <h3>待审核稿</h3>
          <template v-if="selected.pendingRevision">
            <el-input
              v-if="editing"
              v-model="content"
              type="textarea"
              aria-label="修订正文"
              :rows="12"
              maxlength="30000"
            />
            <AiMarkdown v-else :content="content" />
            <AiSources
              :sources="
                selected.pendingRevision.sourceIds.map((segmentId, index) => ({
                  segmentId,
                  label: `核对来源 [${index + 1}]`,
                }))
              "
            />
          </template>
          <p v-else class="ai-empty">暂无待审核稿。</p>
        </div>
      </div>
      <el-form label-position="top" class="wiki-decision" @submit.prevent>
        <el-form-item v-if="selected.pendingRevision" label="审核说明">
          <el-input v-model="note" maxlength="500" />
        </el-form-item>
        <div class="ai-actions">
          <template v-if="selected.pendingRevision">
            <el-button :disabled="busy" @click="editing = !editing">{{
              editing ? '预览修订' : '编辑新稿'
            }}</el-button>
            <el-button :disabled="busy" @click="regenerate">重新生成</el-button>
            <el-button type="primary" :disabled="busy || !content.trim()" @click="decide('publish')"
              >发布</el-button
            >
            <el-button type="danger" plain :disabled="busy" @click="decide('reject')"
              >驳回</el-button
            >
          </template>
          <el-button v-if="selected.currentRevision" :disabled="busy" @click="decide('archive')"
            >归档页面</el-button
          >
          <el-button
            v-if="selected.currentRevision && !selected.pendingRevision"
            :disabled="busy"
            @click="openDraft(selected)"
            >新建修订</el-button
          >
        </div>
      </el-form>
    </section>
    <section class="panel ai-section">
      <h2>体检记录</h2>
      <el-table :data="issues" :empty-text="busy ? '正在读取…' : '暂无待处理问题'">
        <el-table-column prop="title" label="页面标题" min-width="150" />
        <el-table-column label="检查项" min-width="130"
          ><template #default="{ row }">{{
            issueLabels[row.type] ?? row.type
          }}</template></el-table-column
        >
        <el-table-column prop="detail" label="说明" min-width="220" />
        <el-table-column label="状态" width="100"
          ><template #default="{ row }">{{
            issueLabels[row.status] ?? row.status
          }}</template></el-table-column
        >
      </el-table>
    </section>
    <el-dialog
      v-model="draftOpen"
      :title="draftPageId ? '新建修订' : '新建 Wiki 草稿'"
      width="min(540px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="createDraft">
        <el-form-item label="页面标题"
          ><el-input v-model="draftTitle" maxlength="160"
        /></el-form-item>
        <el-form-item label="原文片段">
          <el-select
            v-model="draftSources"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="从分段预览复制片段编号，输入后回车"
          />
        </el-form-item>
        <el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="!draftTitle.trim() || !draftSources.length"
          >生成草稿</el-button
        >
        <el-button :disabled="busy" @click="draftOpen = false">取消</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.wiki-revision {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 14px;
  max-height: 60vh;
  overflow: auto;
}
.wiki-decision {
  margin-top: 16px;
}
</style>
