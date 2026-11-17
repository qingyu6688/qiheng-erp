<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { aiRead, aiWrite } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import type { AiEvalRun, AiEvalSet } from '../../types/ai'
import '../../styles/ai-workspace.css'

const sets = ref<AiEvalSet[]>([])
const setId = ref('')
const runs = ref<AiEvalRun[]>([])
const run = ref<AiEvalRun>()
const error = ref('')
const busy = ref(false)
const canRun = computed(() => setId.value && !runs.value.some((item) => item.status === 'running'))
let active = true
let timer: ReturnType<typeof setTimeout> | undefined
const statusLabels: Record<string, string> = {
  running: '运行中',
  completed: '已完成',
  failed: '失败',
}
const categoryLabels: Record<string, string> = {
  knowledge: '知识问答',
  citation: '来源引用',
  refusal: '拒答',
  security: '安全',
  tool: '工具调用',
  injection: '提示注入',
  tenant: '租户隔离',
  role: '角色访问',
  department: '部门访问',
}
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })
function rate(value?: number): string {
  return typeof value === 'number' ? percent.format(value) : '—'
}

async function perform(action: () => Promise<void>): Promise<void> {
  if (busy.value || !active) return
  busy.value = true
  error.value = ''
  clearTimeout(timer)
  try {
    await action()
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) {
      busy.value = false
      if (run.value?.status === 'running' && !error.value) {
        timer = setTimeout(() => {
          void perform(refreshRuns)
        }, 3000)
      }
    }
  }
}

async function readRun(id: string): Promise<void> {
  const result = await aiRead<AiEvalRun>(`/admin/eval-runs/${encodeURIComponent(id)}`)
  if (active) run.value = result
}

async function refreshRuns(): Promise<void> {
  if (!setId.value) return
  const items = await aiRead<AiEvalRun[]>(`/admin/evals/${encodeURIComponent(setId.value)}/runs`)
  if (!active) return
  runs.value = items
  const id = run.value?.id ?? items[0]?.id
  if (id) await readRun(id)
}

async function load(): Promise<void> {
  const items = await aiRead<AiEvalSet[]>('/admin/evals')
  if (!active) return
  sets.value = items
  if (!items.some((item) => item.id === setId.value)) {
    setId.value = items[0]?.id ?? ''
    run.value = undefined
    runs.value = []
  }
  await refreshRuns()
}

async function selectSet(id: string): Promise<void> {
  if (busy.value) return
  setId.value = id
  run.value = undefined
  runs.value = []
  await perform(refreshRuns)
}

async function start(): Promise<void> {
  if (!canRun.value || !window.confirm('确定用当前路由和助手配置运行此评测集？')) return
  await perform(async () => {
    const result = await aiWrite<AiEvalRun>(
      `/admin/evals/${encodeURIComponent(setId.value)}/run`,
      {},
    )
    if (!active) return
    run.value = result
    await refreshRuns()
  })
}

onMounted(() => perform(load))
onScopeDispose(() => {
  active = false
  clearTimeout(timer)
})
</script>

<template>
  <div class="ai-page">
    <div class="page-heading">
      <div>
        <h1>AI 评测</h1>
        <p>按评测集检查答案、引用、拒答和权限隔离。</p>
      </div>
      <div class="ai-actions">
        <el-button :disabled="busy" @click="perform(load)">刷新</el-button>
        <el-button type="primary" :disabled="busy || !canRun" @click="start">运行评测</el-button>
      </div>
    </div>
    <el-alert v-if="error" class="ai-error" type="error" :title="error" :closable="false" />
    <section class="panel ai-section" :aria-busy="busy">
      <h2>评测集</h2>
      <el-select
        :model-value="setId"
        aria-label="选择评测集"
        placeholder="选择评测集"
        :disabled="busy"
        style="width: min(340px, 100%)"
        @change="selectSet"
      >
        <el-option
          v-for="item in sets"
          :key="item.id"
          :value="item.id"
          :label="`${item.name}（${item.totalCases} 条）`"
        />
      </el-select>
      <p v-if="!sets.length && !busy && !error" class="ai-empty">暂无评测集。</p>
    </section>
    <section v-if="setId" class="panel ai-section">
      <h2>运行记录</h2>
      <el-table :data="runs" :empty-text="busy ? '正在读取…' : '暂无评测记录'">
        <el-table-column prop="id" label="运行编号" min-width="130" />
        <el-table-column label="状态" min-width="110"
          ><template #default="{ row }">{{
            statusLabels[row.status] ?? row.status
          }}</template></el-table-column
        >
        <el-table-column prop="total" label="用例数" width="100" />
        <el-table-column label="正确率" width="110"
          ><template #default="{ row }">{{ rate(row.correctRate) }}</template></el-table-column
        >
        <el-table-column label="引用准确率" width="120"
          ><template #default="{ row }">{{ rate(row.citationRate) }}</template></el-table-column
        >
        <el-table-column label="拒答率" width="100"
          ><template #default="{ row }">{{ rate(row.refusalRate) }}</template></el-table-column
        >
        <el-table-column label="安全通过率" width="120"
          ><template #default="{ row }">{{ rate(row.securityRate) }}</template></el-table-column
        >
        <el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="busy" @click="perform(() => readRun(row.id))"
              >详情</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </section>
    <section v-if="run" class="panel ai-section">
      <div class="page-heading">
        <h2>评测明细 · {{ run.id }}</h2>
        <el-tag
          v-if="run.status === 'completed' && run.passed !== undefined"
          :type="run.passed ? 'success' : 'danger'"
          >{{ run.passed ? '已达标' : '未达标' }}</el-tag
        >
      </div>
      <p v-if="run.status === 'running'" role="status">评测运行中，完成后显示逐条结果。</p>
      <p v-else-if="run.status === 'failed'" role="status">
        本次评测失败，请核对调用日志和模型配置。
      </p>
      <el-table v-else :data="run.results ?? []" empty-text="暂无逐条结果" max-height="520">
        <el-table-column prop="caseId" label="用例编号" min-width="120" />
        <el-table-column label="分类" min-width="110"
          ><template #default="{ row }">{{
            categoryLabels[row.category] ?? row.category
          }}</template></el-table-column
        >
        <el-table-column label="答案" width="100"
          ><template #default="{ row }">{{
            row.correct ? '通过' : '未通过'
          }}</template></el-table-column
        >
        <el-table-column label="引用" width="100"
          ><template #default="{ row }">{{
            row.citationCorrect ? '通过' : '未通过'
          }}</template></el-table-column
        >
        <el-table-column label="拒答（预期 / 实际）" min-width="150"
          ><template #default="{ row }"
            >{{ row.expectedRefusal ? '是' : '否' }} / {{ row.refused ? '是' : '否' }}</template
          ></el-table-column
        >
        <el-table-column prop="reason" label="说明" min-width="210" />
      </el-table>
    </section>
  </div>
</template>
