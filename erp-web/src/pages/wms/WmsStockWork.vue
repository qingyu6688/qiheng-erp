<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError, messageOf } from '../../api/http'
import {
  removeMove,
  saveMove,
  scanCode,
  workCommand,
  workDetail,
  workList,
} from '../../api/wms-operations'
import type { CountJob, CountLine, MoveDocument, MoveLine } from '../../api/wms-operations'
import { useSessionStore } from '../../stores/session'
import type { Id } from '../../types/api'
import { formatDateTimeSeconds } from '../../utils/format'
import { useWmsCommands } from './useWmsCommands'

const props = defineProps<{ warehouse: Id; area: 'moves' | 'replenishments' | 'counts' }>()
const session = useSessionStore()
const { busy, error, notice, run } = useWmsCommands()
const rows = ref<Array<MoveDocument | CountJob>>([])
const nextCursor = ref<Id>()
const listBusy = ref(false)
let listRequest = 0
let detailRequest = 0
let scopeVersion = 0
const move = ref<MoveDocument>()
const count = ref<CountJob>()
const editing = ref(false)
const remark = ref('')
const lines = ref<MoveEntry[]>([])
const countingLocation = ref('')
const counted = ref<CountEntry[]>([])
interface MoveEntry {
  sku: string
  batch: string
  from: string
  to: string
  qty: string
  serials: string
}
interface CountEntry {
  item: CountLine
  qty: string
  serials: string
  cost: string
}
const moveType = computed(() => (props.area === 'replenishments' ? 'replenish' : 'move'))
const canMove = computed(() => session.can('wms_move:manage'))
const canCount = computed(
  () => session.can('wms_task:execute') && session.can('stock_count:record_counts'),
)
const statusNames: Record<string, string> = {
  created: '待领取',
  assigned: '已领取',
  in_progress: '进行中',
  done: '已完成',
  draft: '草稿',
  posted: '已过账',
  cancelled: '已取消',
}

async function load(append = false): Promise<void> {
  const cursor = append ? nextCursor.value : undefined
  if (append && (listBusy.value || !cursor)) return
  const request = ++listRequest
  const params = { warehouseId: props.warehouse, cursor }
  error.value = ''
  listBusy.value = true
  try {
    const page =
      props.area === 'counts'
        ? await workList<CountJob>('count-tasks', params)
        : await workList<MoveDocument>('moves', { ...params, type: moveType.value })
    if (request !== listRequest) return
    rows.value = append ? [...rows.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor ?? undefined
  } catch (cause: unknown) {
    if (request === listRequest) error.value = messageOf(cause)
  } finally {
    if (request === listRequest) listBusy.value = false
  }
}

watch(
  [() => session.companyId, () => props.warehouse, () => props.area],
  () => {
    scopeVersion += 1
    detailRequest += 1
    rows.value = []
    nextCursor.value = undefined
    move.value = undefined
    count.value = undefined
    remark.value = ''
    lines.value = []
    countingLocation.value = ''
    counted.value = []
    editing.value = false
    notice.value = ''
    void load()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  scopeVersion += 1
  detailRequest += 1
  listRequest += 1
})

function addLine(): void {
  lines.value.push({ sku: '', batch: '', from: '', to: '', qty: '', serials: '' })
}

function newMove(): void {
  detailRequest += 1
  move.value = undefined
  remark.value = ''
  lines.value = []
  addLine()
  editing.value = true
}

async function openMove(id: Id): Promise<void> {
  const request = ++detailRequest
  try {
    const result = await workDetail<MoveDocument>('moves', id)
    if (request !== detailRequest) return
    move.value = result
    editing.value = false
  } catch (cause: unknown) {
    if (request === detailRequest) error.value = messageOf(cause)
  }
}

function edit(): void {
  if (!move.value) return
  remark.value = move.value.remark ?? ''
  lines.value = move.value.lines.map((row) => ({
    sku: row.skuCode ?? '',
    batch: row.batchNo ?? '',
    from: row.fromLocationCode ?? '',
    to: row.toLocationCode ?? '',
    qty: row.qty,
    serials: row.serialNos?.join('\n') ?? '',
  }))
  editing.value = true
}

async function parseMoveLine(line: MoveEntry, warehouse: Id): Promise<MoveLine> {
  const asLocation = (code: string) => (code.startsWith('L:') ? code : 'L:' + code)
  const [sku, from, to] = await Promise.all([
    scanCode(line.sku.trim(), warehouse),
    scanCode(asLocation(line.from.trim()), warehouse),
    scanCode(asLocation(line.to.trim()), warehouse),
  ])
  if (sku.type !== 'sku' || from.type !== 'location' || to.type !== 'location')
    throw new ApiError(422, 'VALIDATION_FAILED', '请扫描有效的商品条码和库位码。')
  const batch = line.batch.trim()
  const batchId = batch
    ? (
        await scanCode(
          batch.startsWith('B:') ? batch : 'B:' + (sku.skuCode ?? line.sku.trim()) + '|' + batch,
          warehouse,
        )
      ).id
    : undefined
  return {
    skuId: sku.id,
    batchId,
    fromLocationId: from.id,
    toLocationId: to.id,
    qty: line.qty.trim(),
    serialNos: line.serials
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean),
  }
}

async function save(): Promise<void> {
  const scope = scopeVersion
  const current = move.value
  const payload = {
    warehouseId: props.warehouse,
    remark: remark.value,
    version: current?.version,
    entries: lines.value.map((line) => ({ ...line })),
  }
  const result = await run(
    'move-save:' + (current?.id ?? ''),
    payload,
    async (key) => {
      const details = await Promise.all(
        payload.entries.map((line) => parseMoveLine(line, payload.warehouseId)),
      )
      if (scope !== scopeVersion) return undefined
      const input = {
        warehouseId: payload.warehouseId,
        remark: payload.remark,
        version: payload.version,
        lines: details,
      }
      return current
        ? saveMove(current.id, input, key)
        : workCommand<MoveDocument>('/moves', input, key)
    },
    '移库草稿已保存。',
  )
  if (result && scope === scopeVersion) {
    move.value = result
    editing.value = false
    await load()
  }
}

async function postMove(cancel = false): Promise<void> {
  const scope = scopeVersion
  const current = move.value
  if (!current) return
  const target = props.area === 'replenishments' ? '/replenishments/' : '/moves/'
  const payload = { version: current.version }
  const path = target + current.id + '/post'
  if (
    (await run(
      cancel ? 'move-cancel:' + current.id : path,
      payload,
      async (key) => {
        if (cancel) await removeMove(current.id, current.version, key)
        else await workCommand(path, payload, key)
        return true
      },
      cancel ? '移库单已取消。' : '作业已过账。',
    )) &&
    scope === scopeVersion
  ) {
    await Promise.all([openMove(current.id), load()])
  }
}

async function replenish(): Promise<void> {
  const scope = scopeVersion
  const input = { warehouseId: props.warehouse }
  const result = await run('replenish', input, (key) =>
    workCommand<{ items: MoveDocument[] }>('/replenishments/generate', input, key),
  )
  if (result && scope === scopeVersion) {
    notice.value = result.items.length
      ? '已生成 ' + result.items.length + ' 条补货单。'
      : '当前没有需要补货的拣货位。'
    await load()
  }
}

async function openCount(id: Id): Promise<void> {
  const request = ++detailRequest
  try {
    const result = await workDetail<CountJob>('count-tasks', id)
    if (request !== detailRequest) return
    count.value = result
    countingLocation.value = ''
    counted.value = (result.lines ?? []).map((item) => ({
      item,
      qty: item.countedQty ?? '',
      serials: '',
      cost: '',
    }))
  } catch (cause: unknown) {
    if (request === detailRequest) error.value = messageOf(cause)
  }
}

async function saveCount(finish: boolean): Promise<void> {
  const scope = scopeVersion
  const task = count.value
  if (!task) return
  const path = '/count-tasks/' + task.id + (finish ? '/confirm' : '/claim')
  const data = finish
    ? {
        version: task.version,
        locationCode: countingLocation.value,
        lines: counted.value.map((row) => ({
          lineId: row.item.id,
          countedQty: row.qty,
          inputUnitCost: row.cost || undefined,
          serials: row.serials
            .split(/\r?\n/)
            .map((s) => s.trim())
            .filter(Boolean),
        })),
      }
    : { version: task.version }
  if (
    (await run(
      path,
      data,
      async (key) => {
        await workCommand(path, data, key)
        return true
      },
      finish ? '库位实盘已确认。' : '已领取盘点任务。',
    )) &&
    scope === scopeVersion
  ) {
    await Promise.all([openCount(task.id), load()])
  }
}
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="notice" role="status">{{ notice }}</p>
  <div class="operations">
    <el-button :disabled="busy" :loading="listBusy" @click="load()">刷新</el-button>
    <el-button v-if="area === 'moves' && canMove" type="primary" :disabled="busy" @click="newMove"
      >新建移库</el-button
    >
    <el-button
      v-if="area === 'replenishments' && canMove"
      type="primary"
      :loading="busy"
      @click="replenish"
      >检查并生成补货</el-button
    >
  </div>
  <template v-if="area === 'counts'">
    <el-table :data="rows" :empty-text="listBusy ? '正在读取库位盘点任务' : '暂无库位盘点任务'">
      <el-table-column prop="countNo" label="盘点单" />
      <el-table-column prop="locationCode" label="库位" />
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          statusNames[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link type="primary" :disabled="busy" @click="openCount(row.id)"
            >查看作业</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <div v-if="nextCursor" class="operations">
      <el-button :disabled="busy" :loading="listBusy" @click="load(true)">加载更多</el-button>
    </div>
    <template v-if="count">
      <h3>{{ count.countNo }} · {{ count.locationCode }}</h3>
      <p v-if="count.snapshotAt">盘点快照时间（北京时间）{{ formatDateTimeSeconds(count.snapshotAt) }}</p>
      <el-button
        v-if="canCount && count.status === 'created'"
        type="primary"
        :loading="busy"
        @click="saveCount(false)"
        >领取盘点</el-button
      >
      <el-form
        v-if="
          canCount &&
          count.assigneeId === session.user?.id &&
          ['assigned', 'in_progress'].includes(count.status)
        "
        label-position="top"
        @submit.prevent="saveCount(true)"
      >
        <p>请填写盘点开始时的实盘数量。随后清点时，从清点数量中扣除期间入库、加回期间出库，再录入。</p>
        <el-form-item label="实扫库位"
          ><el-input v-model="countingLocation" aria-label="实扫库位"
        /></el-form-item>
        <el-table :data="counted" empty-text="暂无盘点行">
          <el-table-column label="货品"
            ><template #default="{ row }"
              >{{ row.item.skuCode }} · {{ row.item.skuName }}</template
            ></el-table-column
          >
          <el-table-column label="批次"
            ><template #default="{ row }">{{ row.item.batchNo ?? '—' }}</template></el-table-column
          >
          <el-table-column label="快照账面数"
            ><template #default="{ row }">{{ row.item.bookQty }}</template></el-table-column
          >
          <el-table-column label="快照实盘数"
            ><template #default="{ row }"
              ><el-input v-model="row.qty" aria-label="快照实盘数" /></template
          ></el-table-column>
          <el-table-column label="新增库存单价"
            ><template #default="{ row }"
              ><el-input v-model="row.cost" aria-label="新增库存单价" /></template
          ></el-table-column>
          <el-table-column label="差异序列号"
            ><template #default="{ row }"
              ><el-input
                v-if="row.item.serialManaged"
                v-model="row.serials"
                type="textarea"
                aria-label="差异序列号"
                placeholder="盘盈或盘亏的序列号，每行一个" /></template
          ></el-table-column>
        </el-table>
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="!countingLocation.trim() || counted.some((row) => !row.qty.trim())"
          >提交实盘</el-button
        >
      </el-form>
      <el-table v-else :data="count.lines ?? []" empty-text="暂无盘点行">
        <el-table-column prop="skuName" label="商品" /><el-table-column
          prop="batchNo"
          label="批次"
        />
        <el-table-column prop="bookQty" label="快照账面数" /><el-table-column
          prop="countedQty"
          label="快照实盘数"
        />
      </el-table>
    </template>
  </template>
  <template v-else>
    <el-table
      :data="rows"
      :empty-text="listBusy ? '正在读取单据' : area === 'moves' ? '暂无移库单' : '暂无补货单'"
    >
      <el-table-column prop="no" label="单号" />
      <el-table-column prop="remark" label="备注" />
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          statusNames[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link type="primary" :disabled="busy" @click="openMove(row.id)"
            >查看单据</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <div v-if="nextCursor" class="operations">
      <el-button :disabled="busy" :loading="listBusy" @click="load(true)">加载更多</el-button>
    </div>
    <el-form v-if="editing" label-position="top" @submit.prevent="save">
      <el-form-item label="备注"><el-input v-model="remark" maxlength="200" /></el-form-item>
      <div class="move-line" v-for="(line, index) in lines" :key="index">
        <el-input v-model="line.sku" aria-label="商品条码" placeholder="商品条码或编号" />
        <el-input v-model="line.batch" aria-label="批次" placeholder="批次号，可留空" />
        <el-input v-model="line.from" aria-label="源库位" placeholder="源库位码" />
        <el-input v-model="line.to" aria-label="目标库位" placeholder="目标库位码" />
        <el-input v-model="line.qty" aria-label="移库数量" placeholder="数量" />
        <el-input
          v-model="line.serials"
          type="textarea"
          aria-label="序列号"
          placeholder="每行一个序列号"
        />
        <el-button @click="lines.splice(index, 1)">移除行</el-button>
      </div>
      <div class="operations">
        <el-button @click="addLine">添加明细</el-button>
        <el-button @click="editing = false">关闭编辑</el-button>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!lines.length"
          >保存草稿</el-button
        >
      </div>
    </el-form>
    <section v-else-if="move">
      <h3>{{ move.no }}</h3>
      <el-table :data="move.lines">
        <el-table-column prop="skuName" label="商品" /><el-table-column
          prop="batchNo"
          label="批次"
        />
        <el-table-column prop="fromLocationCode" label="源库位" /><el-table-column
          prop="toLocationCode"
          label="目标库位"
        /><el-table-column prop="qty" label="数量" />
      </el-table>
      <div
        v-if="
          canMove &&
          move.status === 'draft' &&
          (!move.assigneeId || move.assigneeId === session.user?.id)
        "
        class="operations"
      >
        <el-button v-if="moveType === 'move'" @click="edit">编辑草稿</el-button>
        <el-button v-if="moveType === 'move'" @click="postMove(true)">取消草稿</el-button>
        <el-button type="primary" :loading="busy" @click="postMove()">确认过账</el-button>
      </div>
    </section>
  </template>
</template>

<style scoped>
.operations,
.move-line {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.move-line .el-input,
.move-line .el-textarea {
  width: 145px;
}
</style>
