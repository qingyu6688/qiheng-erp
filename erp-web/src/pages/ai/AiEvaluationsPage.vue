<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { aiRead, aiWrite } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import type { AiEvalCase, AiEvalRun, AiEvalSet } from '../../types/ai'
import { useSessionStore } from '../../stores/session'
import '../../styles/ai-workspace.css'

const sets = ref<AiEvalSet[]>([])
const setId = ref('')
const runs = ref<AiEvalRun[]>([])
const run = ref<AiEvalRun>()
const error = ref('')
const busy = ref(false)
const session = useSessionStore()
const canEvaluate = computed(() => session.can('ai_admin:evaluate'))
const businessSet = computed(() => setId.value === 'erp-data-m15')
const explanationSet = computed(() => setId.value === 'erp-explain-m15')
const baselineLabel = computed(() => (explanationSet.value ? '单据解释基准' : '报表基准'))
const needsBaseline = computed(() => businessSet.value || explanationSet.value)
const baseline = ref<Record<string, unknown>>()
const baselineName = ref('')
const canRun = computed(
  () =>
    canEvaluate.value &&
    setId.value &&
    (!needsBaseline.value || baseline.value) &&
    !runs.value.some((item) => item.status === 'running'),
)
const businessMetrics = [
  { key: 'toolSelectionRate', label: '工具选择率' },
  { key: 'parameterRate', label: '参数正确率' },
  { key: 'reportAgreementRate', label: '报表一致率' },
] as const
const businessChecks = [
  { key: 'toolSelected', label: '工具选择' },
  { key: 'parametersCorrect', label: '查询参数' },
  { key: 'reportAgrees', label: '与报表一致' },
] as const
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
  shipping: '发货解释',
  closing: '关账解释',
  company: '公司访问',
  injection: '提示注入',
  tenant: '租户隔离',
  role: '角色访问',
  department: '部门访问',
}
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })
function rate(value?: number | null): string {
  return typeof value === 'number' ? percent.format(value) : '—'
}
function marked(value?: boolean | null): string {
  return typeof value === 'boolean' ? (value ? '通过' : '未通过') : '—'
}

async function readBaseline(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const currentSet = setId.value
  const currentCompany = session.companyId
  const label = baselineLabel.value
  baseline.value = undefined
  baselineName.value = ''
  error.value = ''
  if (!file) return
  try {
    if (file.size > 2 * 1024 * 1024) throw new Error(`${label}文件须小于 2 MB。`)
    const parsed: unknown = JSON.parse(await file.text())
    if (
      !parsed ||
      typeof parsed !== 'object' ||
      Array.isArray(parsed) ||
      !Object.keys(parsed).length
    )
      throw new Error(`${label}须是以场景编号为键的 JSON 对象。`)
    if (JSON.stringify(parsed).length > 500000)
      throw new Error(`压缩后的${label}不能超过 50 万字符。`)
    if (
      !active ||
      currentSet !== setId.value ||
      currentCompany !== session.companyId ||
      input.files?.[0] !== file
    )
      return
    baseline.value = parsed as Record<string, unknown>
    baselineName.value = file.name
  } catch (cause: unknown) {
    if (
      active &&
      currentSet === setId.value &&
      currentCompany === session.companyId &&
      input.files?.[0] === file
    )
      error.value =
        cause instanceof SyntaxError
          ? `${label} JSON 格式无效。`
          : cause instanceof Error
            ? cause.message
            : `无法读取${label}。`
  }
}

async function downloadCases(): Promise<void> {
  await perform(async () => {
    const cases = await aiRead<AiEvalCase[]>(
      `/admin/evals/${encodeURIComponent(setId.value)}/cases`,
    )
    if (!active) return
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(cases, null, 2)], { type: 'application/json' }),
    )
    const link = document.createElement('a')
    link.href = url
    link.download = explanationSet.value ? '单据解释评测用例.json' : '智能查数评测用例.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
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
  baseline.value = undefined
  baselineName.value = ''
  runs.value = []
  await perform(refreshRuns)
}

async function start(): Promise<void> {
  if (!canRun.value || !window.confirm('确定用当前路由和助手配置运行此评测集？')) return
  await perform(async () => {
    const result = await aiWrite<AiEvalRun>(
      `/admin/evals/${encodeURIComponent(setId.value)}/run`,
      businessSet.value
        ? { reportBaseline: baseline.value }
        : explanationSet.value
          ? { documentBaseline: baseline.value }
          : {},
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
        <p>检查知识回答、智能查数、单据解释和权限隔离。</p>
      </div>
      <div class="ai-actions">
        <el-button :disabled="busy" @click="perform(load)">刷新</el-button>
        <el-button v-if="canEvaluate" type="primary" :disabled="busy || !canRun" @click="start"
          >运行评测</el-button
        >
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
      <div v-if="needsBaseline" :key="setId" class="ai-eval-baseline">
        <el-button :disabled="busy" @click="downloadCases">下载评测用例</el-button>
        <template v-if="canEvaluate">
          <label for="ai-eval-baseline">{{ baselineLabel }}文件</label>
          <input
            id="ai-eval-baseline"
            type="file"
            accept=".json,application/json"
            :disabled="busy"
            @change="readBaseline"
          />
          <p v-if="baselineName" role="status">已读取 {{ baselineName }}</p>
          <p v-if="explanationSet">
            按用例场景编号填写现有单据的
            ID、状态、检查结果、原因码及来源路径。详情见单据解释验收文档。
          </p>
          <p v-else>
            以用例中的场景编号为键，填入同一公司对应报表的 status、items 和
            summary。金额与数量使用字符串。
          </p>
        </template>
      </div>
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
        <el-table-column
          v-for="metric in businessSet ? businessMetrics : []"
          :key="metric.key"
          :label="metric.label"
          min-width="125"
        >
          <template #default="{ row }">{{ rate(row[metric.key]) }}</template>
        </el-table-column>
        <el-table-column
          v-if="!businessSet"
          :label="explanationSet ? '解释正确率' : '正确率'"
          width="110"
          ><template #default="{ row }">{{ rate(row.correctRate) }}</template></el-table-column
        >
        <el-table-column
          v-if="!businessSet"
          :label="explanationSet ? '来源一致率' : '引用准确率'"
          width="120"
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
        <el-table-column
          v-for="check in businessSet ? businessChecks : []"
          :key="check.key"
          :label="check.label"
          min-width="120"
        >
          <template #default="{ row }">{{ marked(row[check.key]) }}</template>
        </el-table-column>
        <el-table-column v-if="!businessSet" :label="explanationSet ? '解释' : '答案'" width="100"
          ><template #default="{ row }">{{
            row.correct ? '通过' : '未通过'
          }}</template></el-table-column
        >
        <el-table-column v-if="!businessSet" :label="explanationSet ? '来源' : '引用'" width="100"
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

<style scoped>
.ai-eval-baseline {
  display: grid;
  gap: 10px;
  justify-items: start;
  margin-top: 16px;
}
.ai-eval-baseline p {
  margin: 0;
}
</style>
