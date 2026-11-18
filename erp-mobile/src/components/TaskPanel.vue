<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import ScanFeedback from './ScanFeedback.vue'
import ScanInput from './ScanInput.vue'
import TaskLineEditor from './TaskLineEditor.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { browse, command, idPart, resolveCode, stockWork } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id } from '../types/api'
import type { StockConfirmation, StockLine, StockWork, WorkRow } from '../types/work'
import { remaining } from '../utils/quantity'
import { lineForScan, listRow, pendingLine, statusTitle } from '../utils/work'

const props = defineProps<{ type: 'putaway' | 'pick'; initialId?: Id; initialLineId?: Id }>()
const emit = defineEmits<{ exit: [] }>()
const session = useSession()
const scope = props.type === 'putaway' ? '/wms/tasks' : '/wms/picks'
const title = props.type === 'putaway' ? '上架' : '拣货'
const task = ref<StockWork>()
const selected = ref<StockLine>()
const scan = ref('')
const taskScan = ref('')
const confirmed = ref('')
const scanner = ref<InstanceType<typeof ScanInput>>()
const error = ref('')
const opening = ref(false)
const scanning = ref(false)
const confirming = ref(false)
const { items, cursor, busy, failure, load } = usePage((after) => browse<WorkRow>(scope, {}, after))
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const writable = computed(() => session.profile?.permissions.includes('wms_task:execute'))
const canWork = computed(() => writable.value && task.value?.assigneeId === session.profile?.id
  && ['assigned', 'in_progress'].includes(task.value?.status ?? ''))
const completedLines = computed(() => task.value?.lines.filter((line) => remaining(line.planQty, line.doneQty) === '0').length ?? 0)
const complete = computed(() => !!task.value && completedLines.value === task.value.lines.length)
const nextLine = computed(() => task.value?.lines.find((line) => remaining(line.planQty, line.doneQty) !== '0'))
const working = computed(() => sending.value || scanning.value || confirming.value)

onMounted(() => {
  if (props.initialId) void open(props.initialId)
  else void load()
})

async function open(id: Id): Promise<void> {
  if (opening.value || working.value) return
  opening.value = true
  clearFeedback()
  selected.value = undefined
  scan.value = ''
  try {
    task.value = await stockWork(scope, id)
    if (id === props.initialId) selectRouteLine()
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    opening.value = false
    if (!task.value) void nextTick(() => scanner.value?.focusScanner())
  }
}

function selectRouteLine(): void {
  if (!canWork.value || !task.value || !props.initialLineId) return
  const line = pendingLine(task.value.lines, props.initialLineId)
  if (line) editLine(line)
}

function openScanned(code: string): void {
  const row = items.value.find((entry) => entry.no === code || entry.id === code)
  if (!row && !/^[1-9]\d{0,18}$/.test(code)) {
    error.value = '当前列表未找到作业，请加载更多或按单据选择'
    taskScan.value = ''
    feedback(false)
    scanner.value?.focusScanner()
    return
  }
  void open(row?.id ?? code)
}

function back(): void {
  if (working.value || opening.value) return
  clearFeedback()
  if (selected.value || confirmed.value) {
    selected.value = undefined
    confirmed.value = ''
    return
  }
  if (props.initialId) emit('exit')
  else {
    task.value = undefined
    taskScan.value = ''
    void load()
  }
}

function clearFeedback(): void {
  error.value = ''
  commandError.value = ''
  success.value = ''
}

function editLine(line: StockLine): void {
  confirmed.value = ''
  clearFeedback()
  selected.value = line
}

async function scanProduct(code: string): Promise<void> {
  const current = task.value
  if (!current || !canWork.value || working.value) return
  clearFeedback()
  scanning.value = true
  try {
    const match = await resolveCode(code, current.warehouseId)
    const line = lineForScan(current.lines, match)
    if (!pendingLine(current.lines, line.id)) throw new Error('该作业行已经完成')
    editLine(line)
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    scan.value = ''
    scanning.value = false
    if (!selected.value) void nextTick(() => scanner.value?.focusScanner())
  }
}

async function revise(action: 'claim' | 'finish', shortPick = false): Promise<void> {
  const current = task.value
  if (!current) return
  const path = `${scope}/${idPart(current.id)}/${action}`
  const body = { version: current.version, ...(shortPick ? { shortPick: true } : {}) }
  const done = await run([path, body], async (key) => {
    await command(path, body, key)
    task.value = await stockWork(scope, current.id)
    if (action === 'claim') selectRouteLine()
    return true
  }, action === 'claim' ? '已领取作业' : '作业已完成')
  if (!done) feedback(false)
}

async function confirm(input: StockConfirmation): Promise<void> {
  const current = task.value
  const line = selected.value
  if (!current || !line || !canWork.value || working.value) return
  const company = session.company
  clearFeedback()
  confirming.value = true
  try {
    const target = input.toLocationCode.replace(/^L:/i, '')
    const recommended = line.toLocationCode ?? line.recommendedLocationCode
    if (recommended && target !== recommended
      && props.type === 'putaway') {
      const result = await uni.showModal({ title: '更改上架库位', content: `确认改放到 ${target}？` })
      if (!result.confirm) return
    }
    if (session.company !== company || !canWork.value) return
    const body = { ...input, version: current.version }
    const path = `${scope}/${idPart(current.id)}/lines/${idPart(line.id)}/confirm`
    const done = await run([path, body], async (key) => {
      await command(path, body, key)
      task.value = await stockWork(scope, current.id)
      return true
    }, '本次确认已保存')
    if (!done) {
      feedback(false)
      return
    }
    confirmed.value = `${line.skuCode} × ${input.qty} → ${target}`
    selected.value = undefined
    feedback(true)
    if (complete.value) await revise('finish')
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    confirming.value = false
  }
}

async function shortFinish(): Promise<void> {
  const result = await uni.showModal({
    title: '确认短拣',
    content: '剩余数量需要在原发货单处理，确认结束当前拣货作业？',
  })
  if (result.confirm) await revise('finish', true)
}
</script>

<template>
  <view class="panel" :class="{ 'task-with-footer': (selected && canWork) || confirmed, 'task-with-error': error || commandError }">
    <view class="section-title task-heading">
      <button v-if="task || initialId" class="secondary task-back" role="button" aria-label="返回"
        :disabled="working || opening" @click="back">‹</button>
      <text class="heading">{{ title }}{{ task ? ' ' + task.no : '任务' }}</text>
      <text v-if="task" class="task-progress">{{ completedLines }} / {{ task.lines.length }} 行</text>
    </view>
    <template v-if="!task">
      <ScanInput ref="scanner" v-model="taskScan" label="扫描任务码" :disabled="opening" pda-focus @scan="openScanned" />
      <ScanFeedback v-if="error || failure" :ok="false" title="作业无法打开" :detail="error || failure" />
      <button class="secondary" :disabled="busy || opening" @click="load()">刷新任务</button>
      <WorkList :rows="items.map(listRow)" :more="!!cursor" :busy="busy || opening"
        @select="open" @more="load(true)" />
    </template>
    <TaskLineEditor v-else-if="selected && canWork" :key="selected.id" :line="selected" :task-type="type"
      :busy="working" :failure="error || commandError" @edit="clearFeedback" @confirm="confirm" />
    <template v-else-if="confirmed">
      <ScanFeedback ok :title="title + '成功'" :detail="confirmed" />
      <ScanFeedback v-if="error || commandError" :ok="false" title="作业尚未结束" :detail="error || commandError" />
      <view v-if="nextLine && canWork" class="task-next">
        <text class="muted">下一待处理行</text>
        <text class="subheading">{{ nextLine.skuCode }} {{ nextLine.skuName }}</text>
        <text class="muted">
          {{ nextLine.toLocationCode ?? nextLine.recommendedLocationCode ?? nextLine.fromLocationCode }} ·
          待确认 {{ remaining(nextLine.planQty, nextLine.doneQty) }}
        </text>
      </view>
      <text v-else-if="task.status === 'done'" class="success" role="status">作业已完成</text>
      <view class="task-footer">
        <button class="secondary" :disabled="working" @click="back">返回作业行</button>
        <view v-if="nextLine && canWork" class="task-footer-primary">
          <wd-button role="button" block :round="false" :disabled="working" @click="editLine(nextLine)">继续作业</wd-button>
        </view>
        <view v-else-if="canWork && complete" class="task-footer-primary">
          <wd-button role="button" block :round="false" :loading="working" :disabled="working" @click="revise('finish')">完成任务</wd-button>
        </view>
      </view>
    </template>
    <template v-else>
      <ScanInput v-if="canWork && !complete" ref="scanner" v-model="scan" label="扫描商品或批次"
        :disabled="working" pda-focus @scan="scanProduct" />
      <ScanFeedback v-if="error || commandError" :ok="false" title="作业未处理" :detail="error || commandError" />
      <text v-if="success" class="success" role="status">{{ success }}</text>
      <text class="muted">{{ statusTitle(task.status) }} {{ task.shipmentNo }}</text>
      <wd-button v-if="task.status === 'created' && writable" role="button" block :round="false"
        :loading="working" @click="revise('claim')">
        领取任务
      </wd-button>
      <button v-for="line in task.lines" :key="line.id" class="secondary work-list-row"
        :disabled="working || !canWork || remaining(line.planQty, line.doneQty) === '0'" @click="editLine(line)">
        <text>{{ line.skuCode }} {{ line.skuName }}</text>
        <text class="muted">{{ line.fromLocationCode }} · {{ line.batchNo || '无批次' }} · {{ line.doneQty || '0' }}/{{ line.planQty }}</text>
      </button>
      <button v-if="canWork && complete" :disabled="working" @click="revise('finish')">完成任务</button>
      <button v-if="type === 'pick' && canWork && !complete" class="secondary" :disabled="working" @click="shortFinish">短拣结束</button>
    </template>
  </view>
</template>

<style>
.task-heading { justify-content: flex-start; }
.task-with-error { background: var(--mobile-danger-bg); }
.task-heading .heading { flex: 1; min-width: 0; }
.task-back { flex: 0 0 var(--mobile-touch); height: var(--mobile-touch); padding: 0; font-size: 28px; }
.task-progress { flex: 0 0 auto; color: var(--mobile-muted); white-space: nowrap; font-variant-numeric: tabular-nums; }
.task-next { border: 1px solid var(--mobile-divider); border-radius: var(--mobile-radius); padding: 12px; }
</style>
