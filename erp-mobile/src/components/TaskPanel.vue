<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { browse, command, idPart, resolveCode, stockWork } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id } from '../types/api'
import type { StockLine, StockWork, WorkRow } from '../types/work'
import { quantity, remaining, serialLines } from '../utils/quantity'
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
const from = ref('')
const to = ref('')
const batch = ref('')
const qty = ref('')
const serial = ref('')
const serials = ref<string[]>([])
const fromInput = ref<InstanceType<typeof ScanInput>>()
const toInput = ref<InstanceType<typeof ScanInput>>()
const batchInput = ref<InstanceType<typeof ScanInput>>()
const serialInput = ref<InstanceType<typeof ScanInput>>()
const error = ref('')
const opening = ref(false)
const { items, cursor, busy, failure, load } = usePage((after) => browse<WorkRow>(scope, {}, after))
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const writable = computed(() => session.profile?.permissions.includes('wms_task:execute'))
const canWork = computed(() => writable.value && task.value?.assigneeId === session.profile?.id
  && ['assigned', 'in_progress'].includes(task.value?.status ?? ''))
const complete = computed(() => task.value?.lines.every((line) => remaining(line.planQty, line.doneQty) === '0'))

onMounted(() => {
  if (props.initialId) void open(props.initialId)
  else void load()
})

async function open(id: Id): Promise<void> {
  opening.value = true
  error.value = ''
  selected.value = undefined
  scan.value = ''
  try {
    task.value = await stockWork(scope, id)
    if (id === props.initialId) selectRouteLine()
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    opening.value = false
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
    feedback(false)
    return
  }
  void open(row?.id ?? code)
}

function back(): void {
  if (props.initialId) emit('exit')
  else {
    selected.value = undefined
    task.value = undefined
    taskScan.value = ''
    error.value = ''
    void load()
  }
}

function editLine(line: StockLine): void {
  selected.value = line
  error.value = ''
  from.value = ''
  to.value = ''
  batch.value = ''
  qty.value = remaining(line.planQty, line.doneQty)
  serial.value = ''
  serials.value = []
  void nextTick(() => fromInput.value?.focusScanner())
}

async function scanProduct(code: string): Promise<void> {
  if (!task.value || !canWork.value) return
  error.value = ''
  try {
    const match = await resolveCode(code, task.value.warehouseId)
    editLine(lineForScan(task.value.lines, match))
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    scan.value = ''
  }
}

function confirmSerial(code: string): void {
  if (serials.value.includes(code)) {
    error.value = '序列号已录入'
    feedback(false)
    return
  }
  serials.value.push(code)
  serial.value = ''
  error.value = ''
  feedback(true)
  serialInput.value?.focusScanner()
}

async function revise(action: 'claim' | 'finish', shortPick = false): Promise<void> {
  const current = task.value
  if (!current) return
  const path = `${scope}/${idPart(current.id)}/${action}`
  const body = { version: current.version, ...(shortPick ? { shortPick: true } : {}) }
  await run([path, body], async (key) => {
    await command(path, body, key)
    task.value = await stockWork(scope, current.id)
    if (action === 'claim') selectRouteLine()
    return true
  }, action === 'claim' ? '已领取作业' : '作业已完成')
}

async function confirm(): Promise<void> {
  const current = task.value
  const line = selected.value
  if (!current || !line || sending.value) return
  error.value = ''
  try {
    const fromLocationCode = from.value.trim()
    const toLocationCode = to.value.trim()
    if (!fromLocationCode || !toLocationCode) throw new Error('请扫描来源库位和目标库位')
    const recommended = line.toLocationCode ?? line.recommendedLocationCode
    if (recommended && toLocationCode.replace(/^L:/i, '') !== recommended
      && props.type === 'putaway') {
      const result = await uni.showModal({ title: '更改上架库位', content: `确认改放到 ${toLocationCode}？` })
      if (!result.confirm) return
    }
    const body = {
      fromLocationCode, toLocationCode, batchNo: batch.value || null,
      qty: quantity(qty.value), serialNos: serialLines([...serials.value, serial.value].join('\n')),
      version: current.version,
    }
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
    feedback(true)
    selected.value = undefined
    if (complete.value) await revise('finish')
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
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
  <view class="panel">
    <view class="section-title">
      <text class="heading">{{ title }}{{ task ? ' ' + task.no : '任务' }}</text>
      <button v-if="task || initialId" class="compact secondary" :disabled="sending" @click="back">返回</button>
    </view>
    <text v-if="failure || commandError || error" class="error" role="alert">{{ error || commandError || failure }}</text>
    <text v-if="success" class="success" role="status">{{ success }}</text>
    <template v-if="!task">
      <ScanInput v-model="taskScan" label="扫描任务码" :disabled="opening" pda-focus @scan="openScanned" />
      <button class="secondary" :disabled="busy || opening" @click="load()">刷新任务</button>
      <WorkList :rows="items.map(listRow)" :more="!!cursor" :busy="busy || opening"
        @select="open" @more="load(true)" />
    </template>
    <template v-else>
      <text class="muted">{{ statusTitle(task.status) }} {{ task.shipmentNo }}</text>
      <wd-button v-if="task.status === 'created' && writable" block :loading="sending" @click="revise('claim')">
        领取任务
      </wd-button>
      <template v-if="selected && canWork">
        <text class="subheading">{{ selected.skuCode }} {{ selected.skuName }}</text>
        <text class="muted">待确认 {{ remaining(selected.planQty, selected.doneQty) }}</text>
        <ScanInput ref="fromInput" v-model="from" :label="'来源库位 · ' + (selected.fromLocationCode ?? '')"
          :disabled="sending" @scan="toInput?.focusScanner()" />
        <ScanInput ref="toInput" v-model="to" :label="'目标库位 · ' + (selected.toLocationCode ?? selected.recommendedLocationCode ?? '按现场确认')"
          :disabled="sending" @scan="selected?.batchNo && batchInput?.focusScanner()" />
        <ScanInput v-if="selected.batchNo" ref="batchInput" v-model="batch" :label="'批次 · ' + selected.batchNo" :disabled="sending" />
        <text class="field-label">确认数量</text>
        <input v-model="qty" class="field" type="digit" aria-label="确认数量" :disabled="sending">
        <ScanInput ref="serialInput" v-model="serial" label="序列号" :disabled="sending" @scan="confirmSerial" />
        <view v-if="serials.length" class="chips">
          <button v-for="(code, index) in serials" :key="code" class="compact secondary"
            :disabled="sending" @click="serials.splice(index, 1)">{{ code }} ×</button>
        </view>
        <wd-button block :loading="sending" @click="confirm">{{ sending ? '正在提交' : '确认本次作业' }}</wd-button>
        <button class="secondary" :disabled="sending" @click="selected = undefined">返回作业行</button>
      </template>
      <template v-else>
        <ScanInput v-if="canWork" v-model="scan" label="扫描商品或批次" :disabled="sending" pda-focus @scan="scanProduct" />
        <button v-for="line in task.lines" :key="line.id" class="secondary work-list-row"
          :disabled="sending || !canWork || remaining(line.planQty, line.doneQty) === '0'" @click="editLine(line)">
          <text>{{ line.skuCode }} {{ line.skuName }}</text>
          <text class="muted">{{ line.fromLocationCode }} · {{ line.batchNo || '无批次' }} · {{ line.doneQty || '0' }}/{{ line.planQty }}</text>
        </button>
        <button v-if="canWork && complete" :disabled="sending" @click="revise('finish')">完成任务</button>
        <button v-if="type === 'pick' && canWork && !complete" class="secondary" :disabled="sending" @click="shortFinish">短拣结束</button>
      </template>
    </template>
  </view>
</template>
