<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import TaskPanel from './TaskPanel.vue'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { browse, command, idPart, labels, pack, resolveCode, stockWork, waveInfo } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id } from '../types/api'
import type { PackInput, PackRow, WaveRow, StockWork, WorkRow } from '../types/work'
import { openPdf } from '../utils/openPdf'
import { quantity, serialLines } from '../utils/quantity'
import { lineForScan, listRow, statusTitle } from '../utils/work'

interface BoxDraft {
  id: number
  no: string
  weight: string
  volume: string
  rows: Record<Id, { qty: string; serials: string; scanCode: string }>
}

const props = defineProps<{ type: 'wave' | 'pack' }>()
const session = useSession()
const scope = props.type === 'wave' ? '/wms/waves' : '/wms/picks'
const wave = ref<WaveRow>()
const picked = ref<StockWork>()
const packed = ref<PackRow[]>([])
const packedWarehouse = ref<Id>()
const printing = ref(false)
const routeLine = ref<{ taskId: Id; lineId: Id }>()
const boxes = ref<BoxDraft[]>([])
const active = ref<number>()
const scan = ref('')
const error = ref('')
let boxCounter = 0
const { items, cursor, busy, failure, load } = usePage((after) =>
  browse<WorkRow>(scope, props.type === 'pack' ? { status: 'done' } : {}, after),
)
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const canClaim = computed(() => ['wms_wave:manage', 'wms_task:execute']
  .every((permission) => session.profile?.permissions.includes(permission)))
const canPack = computed(() => session.profile?.permissions.includes('wms_pack:create'))
const canPrint = computed(() => session.profile?.permissions.includes('label:print'))
const rows = computed(() => items.value.filter((row) => props.type !== 'pack' || !row.shortPick).map(listRow))

onMounted(() => load())

function addBox(): void {
  if (!picked.value) return
  const id = ++boxCounter
  boxes.value.push({
    id, no: String(id), weight: '', volume: '',
    rows: Object.fromEntries(picked.value.lines.map((line) => [
      line.id,
      { qty: '', serials: '', scanCode: '' },
    ])),
  })
  active.value = id
}

async function open(id: Id): Promise<void> {
  error.value = ''
  try {
    if (props.type === 'wave') wave.value = await waveInfo(id)
    else {
      picked.value = await stockWork(scope, id)
      boxes.value = []
      boxCounter = 0
      addBox()
    }
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function claim(): Promise<void> {
  const current = wave.value
  if (!current) return
  const path = `${scope}/${idPart(current.id)}/claim`
  const input = { version: current.version }
  await run([path, input], async (key) => {
    await command(path, input, key)
    wave.value = await waveInfo(current.id)
    return true
  }, '已领取波次')
}

async function returnToRoute(): Promise<void> {
  routeLine.value = undefined
  const waveId = wave.value?.id
  if (!waveId) return
  try {
    const updated = await waveInfo(waveId)
    if (wave.value?.id === waveId) wave.value = updated
  } catch (cause: unknown) {
    if (wave.value?.id === waveId) error.value = errorText(cause)
  }
}

async function scanBoxLine(code: string): Promise<void> {
  const current = picked.value
  const box = boxes.value.find((row) => row.id === active.value)
  if (!box || !current) return
  error.value = ''
  try {
    const match = await resolveCode(code, current.warehouseId)
    if (!['sku', 'batch', 'serial'].includes(match.type)) throw new Error('请扫描待装箱商品、批次或序列号')
    const line = lineForScan(current.lines, match)
    if (match.type === 'serial') {
      if (boxes.value.some((other) =>
        Object.values(other.rows).some((entry) => serialLines(entry.serials).includes(match.code)))) {
        throw new Error('该序列号已经装箱')
      }
      const row = box.rows[line.id]!
      const codes = serialLines(row.serials)
      row.serials = [...codes, match.code].join('\n')
      row.qty = String(codes.length + 1)
    } else {
      box.rows[line.id]!.scanCode = code
    }
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    scan.value = ''
  }
}

async function savePack(): Promise<void> {
  const record = picked.value
  if (!record?.shipmentId) return
  error.value = ''
  try {
    const input: PackInput = {
      shipmentId: record.shipmentId,
      boxes: boxes.value.map((box) => {
        const lines = Object.entries(box.rows)
          .filter(([, row]) => row.qty.trim())
          .map(([pickLineId, row]) => ({
            pickLineId, qty: quantity(row.qty), serialNos: serialLines(row.serials),
            scanCode: row.scanCode || undefined,
          }))
        if (!box.no.trim() || !lines.length) throw new Error('每个箱都需箱号和已复核商品')
        return {
          boxNo: box.no.trim(), weightKg: box.weight.trim() || undefined,
          volume: box.volume.trim() || undefined, lines,
        }
      }),
    }
    const stored = await run(input, (key) => pack(input, key), '复核装箱已完成')
    if (stored) {
      packed.value = stored.items
      packedWarehouse.value = record.warehouseId
      picked.value = undefined
      boxes.value = []
    }
    feedback(Boolean(stored))
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  }
}

async function printPacked(): Promise<void> {
  if (printing.value || !packed.value.length) return
  error.value = ''
  printing.value = true
  try {
    await openPdf(await labels(packed.value.map((box) => ({ type: 'box', id: box.id, copies: 1 })),
      '50x30', packedWarehouse.value))
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    printing.value = false
  }
}
</script>

<template>
  <TaskPanel v-if="routeLine" :key="routeLine.lineId" type="pick" :initial-id="routeLine.taskId"
    :initial-line-id="routeLine.lineId" @exit="returnToRoute" />
  <view v-else class="panel">
    <view class="section-title">
      <text class="heading">{{ type === 'wave' ? '波次' : '复核装箱' }}</text>
      <button v-if="wave || picked || packed.length" class="compact secondary" :disabled="sending || printing"
        @click="wave = undefined; picked = undefined; packed = []; load()">返回</button>
    </view>
    <text v-if="error || failure || commandError" class="error" role="alert">{{ error || failure || commandError }}</text>
    <text v-if="success" class="success" role="status">{{ success }}</text>
    <template v-if="wave">
      <text class="subheading">{{ wave.no }} · {{ statusTitle(wave.status) }}</text>
      <wd-button v-if="canClaim && wave.status === 'created'" block :loading="sending" @click="claim">领取整个波次</wd-button>
      <button v-for="line in wave.lines" :key="line.id" class="secondary work-list-row" :disabled="sending"
        @click="routeLine = { taskId: line.taskId, lineId: line.id }">
        <text>{{ line.fromLocationCode }} · {{ line.skuCode }} {{ line.skuName }}</text>
        <text class="muted">{{ line.shipmentNo }} · {{ line.doneQty }}/{{ line.planQty }}</text>
      </button>
    </template>
    <template v-else-if="packed.length">
      <view v-for="box in packed" :key="box.id" class="line-edit">
        <text>{{ box.shipmentNo }} · 箱 {{ box.boxNo }}</text>
        <text v-if="box.weightKg" class="muted">{{ box.weightKg }} 公斤</text>
      </view>
      <wd-button v-if="canPrint" block :loading="printing" @click="printPacked">打印本单箱标签</wd-button>
    </template>
    <template v-else-if="picked">
      <text class="subheading">{{ picked.shipmentNo ?? picked.no }}</text>
      <text v-if="!canPack" class="muted">当前账号没有复核装箱权限</text>
      <template v-else>
        <view v-for="box in boxes" :key="box.id" class="line-edit">
          <view class="section-title">
            <button class="compact secondary" :class="{ selected: active === box.id }"
              :disabled="sending" @click="active = box.id">箱 {{ box.no }} · {{ active === box.id ? '扫码中' : '选择' }}</button>
            <button v-if="boxes.length > 1" class="compact secondary" :disabled="sending"
              @click="boxes = boxes.filter((entry) => entry.id !== box.id)">移除本箱</button>
          </view>
          <input v-model="box.no" class="field" placeholder="箱号" aria-label="箱号" :disabled="sending">
          <view class="inline">
            <input v-model="box.weight" class="field" type="digit" placeholder="重量（公斤）" aria-label="箱重量" :disabled="sending">
            <input v-model="box.volume" class="field" type="digit" placeholder="体积（可选）" aria-label="箱体积" :disabled="sending">
          </view>
          <ScanInput v-if="active === box.id" v-model="scan" label="扫描商品、批次或序列号"
            :disabled="sending" @scan="scanBoxLine" />
          <view v-for="line in picked.lines" :key="line.id" class="line-edit">
            <text>{{ line.skuCode }} {{ line.batchNo }} · 已拣 {{ line.doneQty }}</text>
            <text v-if="box.rows[line.id]?.scanCode" class="muted">已扫描 {{ box.rows[line.id]?.scanCode }}</text>
            <input v-model="box.rows[line.id]!.qty" class="field" type="digit" placeholder="本箱数量"
              :aria-label="'本箱数量 ' + line.skuCode" :disabled="sending">
            <textarea v-model="box.rows[line.id]!.serials" class="field field-text" placeholder="序列号，每行一个"
              :aria-label="'序列号 ' + line.skuCode" :disabled="sending" auto-height />
          </view>
        </view>
        <button class="secondary" :disabled="sending" @click="addBox()">增加一箱</button>
        <wd-button block :loading="sending" @click="savePack">完成复核装箱</wd-button>
      </template>
    </template>
    <WorkList v-else :rows="rows" :more="!!cursor" :busy="busy" @select="open" @more="load(true)" />
  </view>
</template>
