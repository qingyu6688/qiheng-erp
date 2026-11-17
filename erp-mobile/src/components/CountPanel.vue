<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { browse, command, idPart, resolveCode, stockWork } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id } from '../types/api'
import type { StockWork, WorkRow } from '../types/work'
import { quantity, serialLines } from '../utils/quantity'
import { lineForScan, listRow, statusTitle } from '../utils/work'

const path = '/wms/count-tasks'
const session = useSession()
const task = ref<StockWork>()
const activeLine = ref<Id>()
const location = ref('')
const scan = ref('')
const error = ref('')
const rows = reactive<Record<Id, { qty: string; serial: string; cost: string }>>({})
const { items, cursor, busy, failure, load } = usePage((after) => browse<WorkRow>(path, {}, after))
const { busy: sending, failure: commandError, success, run } = useTaskFlow()

onMounted(() => load())

async function open(id: Id): Promise<void> {
  error.value = ''
  try {
    const result = await stockWork(path, id)
    for (const line of result.lines) rows[line.id] = { qty: line.countedQty ?? '', serial: '', cost: '' }
    task.value = result
    location.value = ''
    activeLine.value = undefined
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function select(code: string): Promise<void> {
  error.value = ''
  if (!task.value) return
  try {
    activeLine.value = lineForScan(task.value.lines, await resolveCode(code, task.value.warehouseId)).id
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    scan.value = ''
  }
}

async function submit(action: 'claim' | 'confirm'): Promise<void> {
  const current = task.value
  if (!current) return
  const url = `${path}/${idPart(current.id)}/${action}`
  error.value = ''
  try {
    const body = action === 'claim' ? { version: current.version } : {
      version: current.version,
      locationCode: location.value,
      lines: current.lines.map((line) => {
        const draft = rows[line.id]!
        return {
          lineId: line.id, countedQty: quantity(draft.qty, true),
          inputUnitCost: draft.cost.trim() || undefined, serials: serialLines(draft.serial),
        }
      }),
    }
    if (action === 'confirm' && !location.value) throw new Error('请先扫盘点库位')
    const done = await run([url, body], async (key) => {
      await command(url, body, key)
      task.value = await stockWork(path, current.id)
      return true
    }, action === 'claim' ? '已领取盘点任务' : '盘点结果已保存')
    feedback(Boolean(done))
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  }
}
</script>

<template>
  <view class="panel">
    <view class="section-title">
      <text class="heading">库位盘点</text>
      <button v-if="task" class="compact secondary" :disabled="sending" @click="task = undefined; load()">返回</button>
    </view>
    <text v-if="error || failure || commandError" class="error" role="alert">{{ error || failure || commandError }}</text>
    <text v-if="success" class="success">{{ success }}</text>
    <WorkList v-if="!task" :rows="items.map(listRow)" :more="!!cursor" :busy="busy" @select="open" @more="load(true)" />
    <template v-else>
      <text class="subheading">{{ task.locationCode }} · {{ statusTitle(task.status) }}</text>
      <wd-button v-if="task.status === 'created'" block :loading="sending" @click="submit('claim')">领取盘点任务</wd-button>
      <template v-if="task.status === 'assigned' && task.assigneeId === session.profile?.id">
        <ScanInput v-model="location" label="盘点库位" :disabled="sending" pda-focus />
        <ScanInput v-model="scan" label="扫描商品或批次选行" :disabled="sending" @scan="select" />
        <view v-for="line in task.lines" :key="line.id" class="line-edit"
          :class="{ highlighted: line.id === activeLine }">
          <text>{{ line.skuCode }} {{ line.skuName }} {{ line.batchNo }}</text>
          <input v-model="rows[line.id]!.qty" class="field" type="digit" placeholder="实盘数量"
            :aria-label="'实盘数量 ' + line.skuCode" :disabled="sending">
          <input v-model="rows[line.id]!.cost" class="field" type="digit" placeholder="盘盈单价（按业务规则填写）"
            :aria-label="'盘盈单价 ' + line.skuCode" :disabled="sending">
          <textarea v-if="line.serialManaged" v-model="rows[line.id]!.serial" class="field field-text"
            placeholder="差异序列号，每行一个，零差额留空"
            :aria-label="'差异序列号 ' + line.skuCode" :disabled="sending" auto-height />
        </view>
        <wd-button block :loading="sending" @click="submit('confirm')">确认盘点</wd-button>
      </template>
    </template>
  </view>
</template>
