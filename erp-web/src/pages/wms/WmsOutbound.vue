<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Ref } from 'vue'
import { isCancelled, messageOf } from '../../api/http'
import { createLabels, scanCode, workCommand, workDetail, workList } from '../../api/wms-operations'
import type { PackBox, PickLine, PickTask, PickWave, Scan } from '../../api/wms-operations'
import { useSessionStore } from '../../stores/session'
import type { Id } from '../../types/api'
import { demandExceedsAvailable } from '../../types/inventory'
import PutawayConfirmForm from './PutawayConfirmForm.vue'
import WmsPackingDocuments from './WmsPackingDocuments.vue'
import type { ShipmentBox } from './packing-documents'
import { useWmsCommands } from './useWmsCommands'

const props = defineProps<{ warehouse: Id; area: 'picks' | 'waves' | 'packs' }>()
const session = useSessionStore()
const { busy, error, notice, run } = useWmsCommands()
const tasks = ref<PickTask[]>([])
const waves = ref<PickWave[]>([])
const packs = ref<ShipmentBox[]>([])
const packingShipment = ref<ShipmentBox>()
const cursors = ref<Record<typeof props.area, Id | null>>({ picks: null, waves: null, packs: null })
const loading = ref(false)
const company = session.companyId
let mounted = true
onBeforeUnmount(() => {
  mounted = false
})
const task = ref<PickTask>()
const wave = ref<PickWave>()
const waveView = ref<'route' | 'sorting'>('route')
const confirmation = ref<PickLine>()
const shipmentIds = ref<Id[]>([])
const rule = ref('manual')
const cutoffAt = ref('')
const ready = ref<Id>('')
const boxes = ref<
  Array<{ boxNo: string; weightKg: string; volume: string; scan: string; lines: BoxLine[] }>
>([])
const statusNames: Record<string, string> = {
  created: '待领取',
  assigned: '已领取',
  in_progress: '进行中',
  done: '已完成',
  cancelled: '已取消',
  short: '短拣',
  short_picked: '短拣',
}
interface BoxLine {
  pickLineId: Id
  qty: string
  serialText: string
  scanCode?: string
}
const canPick = computed(() => session.can('wms_task:execute'))
const claimable = computed(() => task.value?.status === 'created' && !task.value.waveId)
const assigned = computed(
  () =>
    task.value?.assigneeId === session.user?.id &&
    ['assigned', 'in_progress'].includes(task.value?.status ?? ''),
)
const lineForForm = computed(
  () =>
    confirmation.value && {
      ...confirmation.value,
      batchNo: confirmation.value.batchNo ?? null,
      recommendedLocationCode: confirmation.value.toLocationCode,
    },
)
const waveShipments = computed(() => {
  const groups = new Map<Id, { id: Id; no: string; lines: PickLine[] }>()
  for (const line of wave.value?.lines ?? []) {
    const id = line.shipmentId ?? line.shipmentNo ?? ''
    const group = groups.get(id) ?? { id, no: line.shipmentNo ?? id, lines: [] }
    group.lines.push(line)
    groups.set(id, group)
  }
  return [...groups.values()]
})

function isCurrent(): boolean {
  return mounted && session.companyId === company
}

async function readPage<T extends { id: Id; warehouseId: Id }>(
  resource: typeof props.area,
  rows: Ref<T[]>,
  more: boolean,
): Promise<void> {
  const warehouse = props.warehouse
  const cursor = more ? cursors.value[resource] : null
  if (!isCurrent() || (more && !cursor)) return
  const page = await workList<T>(resource, { warehouseId: warehouse, cursor: cursor ?? undefined })
  if (!isCurrent() || warehouse !== props.warehouse) return
  const existing = new Map((more ? rows.value : []).map((row) => [row.id, row]))
  for (const row of page.items) if (row.warehouseId === warehouse) existing.set(row.id, row)
  rows.value = [...existing.values()]
  cursors.value[resource] = page.nextCursor
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    packingShipment.value = undefined
    if (session.can('wms_task:view')) {
      await readPage('picks', tasks, false)
    }
    if (props.area === 'waves') await readPage('waves', waves, false)
    if (props.area === 'packs') await readPage('packs', packs, false)
  } catch (cause: unknown) {
    if (isCurrent() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function loadMore(resource: typeof props.area): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    if (resource === 'picks') await readPage('picks', tasks, true)
    if (resource === 'waves') await readPage('waves', waves, true)
    if (resource === 'packs') await readPage('packs', packs, true)
  } catch (cause: unknown) {
    if (isCurrent() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}

async function openTask(id: Id): Promise<void> {
  confirmation.value = undefined
  try {
    task.value = await workDetail<PickTask>('picks', id)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}

async function openWave(id: Id): Promise<void> {
  try {
    const next = await workDetail<PickWave>('waves', id)
    if (isCurrent()) {
      if (wave.value?.id !== id) waveView.value = 'route'
      wave.value = next
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}

async function taskAction(action: string, input: Record<string, unknown> = {}): Promise<void> {
  const current = task.value
  if (!current) return
  const payload = { version: current.version, ...input }
  const path = '/picks/' + current.id + '/' + action
  if (
    await run(
      path,
      payload,
      async (key) => {
        await workCommand(path, payload, key)
        return true
      },
      '拣货任务已更新。',
    )
  ) {
    await openTask(current.id)
    await load()
  }
}

function confirm(input: Scan): void {
  if (confirmation.value) void taskAction('lines/' + confirmation.value.id + '/confirm', input)
}

async function createWave(): Promise<void> {
  const input = {
    shipmentIds: shipmentIds.value,
    rule: rule.value,
    cutoffAt:
      rule.value === 'cutoff' && cutoffAt.value
        ? new Date(cutoffAt.value).toISOString()
        : undefined,
  }
  const result = await run(
    'wave',
    input,
    (key) => workCommand<PickWave>('/waves', input, key),
    '波次已创建。',
  )
  if (result) {
    shipmentIds.value = []
    await load()
    if (result.id) await openWave(result.id)
  }
}

async function claimWave(): Promise<void> {
  if (!wave.value) return
  const { id, version } = wave.value
  const path = '/waves/' + id + '/claim'
  if (
    await run(
      path,
      { version },
      async (key) => {
        await workCommand(path, { version }, key)
        return true
      },
      '已领取波次。',
    )
  ) {
    await openWave(id)
    await load()
  }
}

async function startPack(id: Id): Promise<void> {
  if (!id) return
  await openTask(id)
  if (task.value?.id !== id) return
  boxes.value = []
  addBox()
}

function addBox(): void {
  boxes.value.push({
    boxNo: String(boxes.value.length + 1).padStart(2, '0'),
    weightKg: '',
    volume: '',
    scan: '',
    lines: [],
  })
}

async function scanPack(index: number): Promise<void> {
  const box = boxes.value[index]
  if (!box?.scan.trim()) return
  const code = box.scan.trim()
  const scanned = await run('pack-scan', code, () => scanCode(code, props.warehouse))
  if (!scanned) return
  const candidates = (task.value?.lines ?? []).filter(
    (line) =>
      line.skuId === (scanned.skuId ?? scanned.id) &&
      (scanned.type !== 'batch' || scanned.batchNo === line.batchNo) &&
      (scanned.type !== 'serial' || line.serialNos?.includes(code)),
  )
  const item = candidates[0]
  if (!item || candidates.length !== 1 || !['sku', 'batch', 'serial'].includes(scanned.type)) {
    error.value = '扫码无法定位唯一的拣货行，请核对商品、批次或序列号。'
    return
  }
  if (item.serialNos?.length && scanned.type !== 'serial') {
    error.value = '该商品需逐件扫描序列号。'
    return
  }
  if (
    scanned.type === 'serial' &&
    boxes.value.some((record) =>
      record.lines.some((line) => line.serialText.split(/\s+/).includes(code)),
    )
  ) {
    error.value = '该序列号已计入装箱明细。'
    return
  }
  box.lines.push({
    pickLineId: item.id,
    qty: '1',
    serialText: scanned.type === 'serial' ? code : '',
    scanCode: scanned.type === 'serial' ? undefined : code,
  })
  box.scan = ''
}

async function pack(): Promise<void> {
  if (!task.value || !boxes.value.length) return
  const payload = {
    shipmentId: task.value.shipmentId,
    boxes: boxes.value.map((box) => ({
      boxNo: box.boxNo.trim(),
      weightKg: box.weightKg || undefined,
      volume: box.volume || undefined,
      lines: box.lines.map((line) => ({
        pickLineId: line.pickLineId,
        qty: line.qty,
        scanCode: line.scanCode,
        serialNos: line.serialText.split(/\s+/).filter(Boolean),
      })),
    })),
  }
  if (
    await run(
      'pack',
      payload,
      async (key) => {
        await workCommand('/packs', payload, key)
        return true
      },
      '复核装箱已保存。',
    )
  ) {
    boxes.value = []
    ready.value = ''
    await load()
  }
}

function printWave(): void {
  const print = window.open('', '_blank', 'popup')
  if (!print) {
    error.value = '请允许浏览器打开拣货单打印窗口。'
    return
  }
  print.opener = null
  const title = print.document.createElement('h1')
  title.textContent = '波次拣货单 ' + (wave.value?.no ?? '')
  const table = print.document.createElement('table')
  const header = table.insertRow()
  for (const name of ['发货单', '商品', '批次', '源库位', '目标库位', '计划数量'])
    header.insertCell().textContent = name
  for (const line of wave.value?.lines ?? []) {
    const row = table.insertRow()
    for (const value of [
      line.shipmentNo,
      line.skuName,
      line.batchNo,
      line.fromLocationCode,
      line.toLocationCode,
      line.planQty,
    ])
      row.insertCell().textContent = value ?? ''
  }
  print.document.body.append(title, table)
  print.focus()
  print.print()
}

async function printBox(box: PackBox): Promise<void> {
  if (!isCurrent() || !session.can('label:print')) return
  const file = await run('box-label', box.id, () =>
    createLabels(props.warehouse, '70x40', 'pdf', [{ id: box.id, type: 'box', copies: 1 }]),
  )
  if (file && isCurrent() && session.can('label:print')) {
    const address = URL.createObjectURL(file)
    const link = document.createElement('a')
    link.href = address
    link.download = '箱标签.pdf'
    link.click()
    setTimeout(() => URL.revokeObjectURL(address), 1000)
  }
}
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="notice" role="status">{{ notice }}</p>
  <el-button :disabled="busy" :loading="loading" @click="load">刷新</el-button>

  <template v-if="area === 'picks'">
    <el-table :data="tasks" empty-text="暂无拣货任务">
      <el-table-column prop="no" label="拣货单" />
      <el-table-column prop="shipmentNo" label="发货单" />
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          statusNames[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="120"
        ><template #default="{ row }">
          <el-button link type="primary" @click="openTask(row.id)">查看任务</el-button>
        </template></el-table-column
      >
    </el-table>
    <el-button v-if="cursors.picks" :disabled="busy || loading" @click="loadMore('picks')">
      加载更多拣货任务
    </el-button>
    <div v-if="task" class="wms-detail">
      <h3>{{ task.no }} · {{ task.shipmentNo }}</h3>
      <div v-if="canPick" class="wms-actions">
        <el-button v-if="claimable" :disabled="busy" type="primary" @click="taskAction('claim')"
          >领取任务</el-button
        >
        <el-button v-if="assigned" :disabled="busy" type="primary" @click="taskAction('finish')"
          >完成拣货</el-button
        >
        <el-button
          v-if="assigned"
          :disabled="busy"
          @click="taskAction('finish', { shortPick: true })"
          >登记短拣</el-button
        >
      </div>
      <el-table :data="task.lines ?? []" empty-text="暂无可拣明细">
        <el-table-column prop="skuName" label="商品" />
        <el-table-column prop="batchNo" label="批次" />
        <el-table-column prop="fromLocationCode" label="源库位" />
        <el-table-column prop="toLocationCode" label="发货库位" />
        <el-table-column prop="planQty" label="计划数量" />
        <el-table-column prop="doneQty" label="已拣数量" />
        <el-table-column v-if="canPick && assigned" label="操作" width="120"
          ><template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="busy || !demandExceedsAvailable(row.planQty, row.doneQty)"
              @click="confirmation = row"
              >扫码确认</el-button
            >
          </template></el-table-column
        >
      </el-table>
      <PutawayConfirmForm
        v-if="lineForForm"
        :key="lineForForm.id"
        :line="lineForForm"
        :busy="busy"
        @confirm="confirm"
        @cancel="confirmation = undefined"
      />
    </div>
  </template>

  <template v-else-if="area === 'waves'">
    <el-form
      v-if="session.can('wms_wave:manage')"
      label-position="top"
      @submit.prevent="createWave"
    >
      <el-form-item label="待拣发货单">
        <el-select v-model="shipmentIds" aria-label="待拣发货单" multiple filterable>
          <el-option
            v-for="row in tasks.filter((item) => item.status === 'created' && !item.waveId)"
            :key="row.id"
            :label="row.shipmentNo"
            :value="row.shipmentId"
          />
        </el-select>
        <el-button v-if="cursors.picks" :disabled="busy || loading" @click="loadMore('picks')">
          加载更多待拣发货单
        </el-button>
      </el-form-item>
      <el-form-item label="组波规则">
        <el-select v-model="rule" aria-label="组波规则">
          <el-option value="manual" label="手工选择" />
          <el-option value="carrier" label="同承运商" />
          <el-option value="customer" label="同客户" />
          <el-option value="cutoff" label="截单时间" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="rule === 'cutoff'" label="截单时间"
        ><el-input v-model="cutoffAt" type="datetime-local"
      /></el-form-item>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        :disabled="shipmentIds.length < 2 || (rule === 'cutoff' && !cutoffAt)"
        >创建波次</el-button
      >
    </el-form>
    <el-table :data="waves" empty-text="暂无波次">
      <el-table-column prop="no" label="波次单号" />
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          statusNames[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="120"
        ><template #default="{ row }">
          <el-button link type="primary" @click="openWave(row.id)">查看路径</el-button>
        </template></el-table-column
      >
    </el-table>
    <el-button v-if="cursors.waves" :disabled="busy || loading" @click="loadMore('waves')">
      加载更多波次
    </el-button>
    <div v-if="wave" class="wms-detail">
      <h3>{{ wave.no }}</h3>
      <div class="wms-actions">
        <el-button
          v-if="session.can('wms_wave:manage') && wave.status === 'created'"
          type="primary"
          :loading="busy"
          @click="claimWave"
          >领取波次</el-button
        >
        <el-button @click="printWave">打印拣货单</el-button>
      </div>
      <el-tabs v-model="waveView" aria-label="波次视图">
        <el-tab-pane name="route" label="拣货路径" />
        <el-tab-pane name="sorting" label="按发货单播种" />
      </el-tabs>
      <el-table v-if="waveView === 'route'" :data="wave.lines ?? []" empty-text="暂无拣货路径">
        <el-table-column prop="shipmentNo" label="发货单" />
        <el-table-column prop="fromLocationCode" label="源库位" />
        <el-table-column prop="skuName" label="商品" />
        <el-table-column prop="batchNo" label="批次" />
        <el-table-column prop="planQty" label="计划数量" />
        <el-table-column prop="doneQty" label="已拣数量" />
      </el-table>
      <template v-else>
        <el-empty v-if="!waveShipments.length" description="暂无播种明细" />
        <section v-for="shipment in waveShipments" :key="shipment.id" class="wms-detail">
          <h4>发货单 {{ shipment.no }}</h4>
          <el-table :data="shipment.lines">
            <el-table-column prop="skuCode" label="SKU" />
            <el-table-column prop="skuName" label="商品" />
            <el-table-column prop="batchNo" label="批次" />
            <el-table-column prop="toLocationCode" label="发货库位" />
            <el-table-column prop="planQty" label="计划数量" />
            <el-table-column prop="doneQty" label="已拣数量" />
          </el-table>
        </section>
      </template>
    </div>
  </template>

  <template v-else>
    <el-form
      v-if="session.can('wms_pack:create') && session.can('wms_task:view')"
      label-position="top"
      @submit.prevent="pack"
    >
      <el-form-item label="已完成拣货单">
        <el-select v-model="ready" aria-label="已完成拣货单" @change="startPack">
          <el-option
            v-for="row in tasks.filter((item) => item.status === 'done')"
            :key="row.id"
            :label="row.shipmentNo"
            :value="row.id"
          />
        </el-select>
        <el-button v-if="cursors.picks" :disabled="busy || loading" @click="loadMore('picks')">
          加载更多已完成拣货单
        </el-button>
      </el-form-item>
      <div v-for="(box, index) in boxes" :key="index" class="wms-box">
        <div class="wms-actions">
          <el-input v-model="box.boxNo" aria-label="箱号" placeholder="箱号" maxlength="32" />
          <el-input v-model="box.weightKg" aria-label="重量（千克）" placeholder="重量（千克）" />
          <el-input v-model="box.volume" aria-label="体积" placeholder="体积" />
          <el-button @click="boxes.splice(index, 1)">移除箱</el-button>
        </div>
        <div class="wms-actions">
          <el-input
            v-model="box.scan"
            aria-label="复核扫码"
            placeholder="商品、批次或序列号"
            @keydown.enter.prevent="scanPack(index)"
          />
          <el-button :disabled="busy || !box.scan.trim()" @click="scanPack(index)"
            >加入实扫明细</el-button
          >
        </div>
        <div v-for="(line, lineIndex) in box.lines" :key="lineIndex" class="wms-actions">
          <el-select v-model="line.pickLineId" aria-label="装箱拣货行">
            <el-option
              v-for="item in task?.lines ?? []"
              :key="item.id"
              :label="item.skuCode + ' · ' + (item.batchNo ?? '无批次')"
              :value="item.id"
            />
          </el-select>
          <el-input v-model="line.qty" aria-label="复核数量" placeholder="复核数量" />
          <el-input
            v-model="line.serialText"
            type="textarea"
            aria-label="复核序列号"
            placeholder="序列号，每行一个"
          />
          <el-button @click="box.lines.splice(lineIndex, 1)">移除行</el-button>
        </div>
        <el-button @click="box.lines.push({ pickLineId: '', qty: '', serialText: '' })"
          >添加实扫明细</el-button
        >
      </div>
      <div v-if="ready" class="wms-actions">
        <el-button @click="addBox">增加箱</el-button>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!boxes.length"
          >确认装箱</el-button
        >
      </div>
    </el-form>
    <el-table :data="packs" empty-text="暂无装箱记录">
      <el-table-column prop="boxNo" label="箱号" />
      <el-table-column prop="shipmentNo" label="发货单" />
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          statusNames[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="200"
        ><template #default="{ row }">
          <template v-if="row.status === 'done'">
            <el-button link type="primary" @click="packingShipment = row">查看整单</el-button>
            <el-button v-if="session.can('label:print')" link type="primary" @click="printBox(row)">
              下载箱标签
            </el-button>
          </template>
        </template></el-table-column
      >
    </el-table>
    <el-button v-if="cursors.packs" :disabled="busy || loading" @click="loadMore('packs')">
      加载更多装箱记录
    </el-button>
    <WmsPackingDocuments
      v-if="packingShipment"
      :key="packingShipment.shipmentId"
      :shipment="packingShipment"
    />
  </template>
</template>

<style scoped>
.wms-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0;
}
.wms-actions > .el-input,
.wms-actions > .el-textarea,
.wms-actions > .el-select {
  width: 160px;
}
.wms-detail {
  margin-top: 20px;
}
.wms-box {
  padding: 12px 0;
  border-top: 1px solid var(--el-border-color-light);
}
</style>
