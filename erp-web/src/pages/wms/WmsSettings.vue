<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, messageOf } from '../../api/http'
import { warehouseLayout } from '../../api/supply-chain'
import {
  createLabels,
  locationRules,
  saveLocationRule,
  scanCode,
  taskAssignees,
  workCommand,
  workList,
  workStatistics,
} from '../../api/wms-operations'
import type { JobRow, LabelCode, LocationRule } from '../../api/wms-operations'
import { useSessionStore } from '../../stores/session'
import type { Id } from '../../types/api'
import { useWmsCommands } from './useWmsCommands'

const props = defineProps<{ warehouse: Id; area: 'rules' | 'tasks' | 'labels' }>()
const session = useSessionStore()
const { busy, error, notice, run } = useWmsCommands()
const tasks = ref<JobRow[]>([])
const kind = ref('')
const status = ref('')
const mine = ref(false)
const taskCursor = ref<Id>()
const loadingTasks = ref(false)
let taskRequest = 0
const stats = ref<Array<Record<string, string | number | null>>>([])
const begin = ref('')
const end = ref('')
const editing = ref<JobRow>()
const operator = ref<Id>('')
const assignees = ref<Array<{ id: Id; displayName: string }>>([])
const searchingAssignees = ref(false)
let assigneeRequest = 0
const rules = ref<LocationRule[]>([])
const rule = ref<LocationRule>()
const skuScan = ref('')
const layout = ref<Awaited<ReturnType<typeof warehouseLayout>>>()
const labelScan = ref('')
const labelType = ref<LabelCode['type']>('sku')
const labelId = ref<Id>('')
const queue = ref<Array<LabelCode & { copies: number }>>([])
const size = ref('70x40')
const format = ref('pdf')
const types: Record<string, string> = {
  putaway: '上架',
  pick: '拣货',
  replenish: '补货',
  move: '移库',
  count: '盘点',
}
const states: Record<string, string> = {
  created: '待领取',
  assigned: '已领取',
  in_progress: '进行中',
  done: '已完成',
  cancelled: '已取消',
}
const labels: Record<string, string> = { sku: '商品', location: '库位', batch: '批次', box: '箱' }

async function load(): Promise<void> {
  error.value = ''
  try {
    if (props.area === 'tasks') return await loadTasks()
    if (props.area === 'rules') {
      const result = await locationRules(props.warehouse)
      rules.value = Array.isArray(result) ? result : result.items
      if (session.can('wms_location:view') && session.can('warehouse:view'))
        layout.value = await warehouseLayout(props.warehouse)
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
onMounted(load)

async function loadTasks(append = false): Promise<void> {
  const request = ++taskRequest
  const cursor = append ? taskCursor.value : undefined
  if (!append) {
    tasks.value = []
    taskCursor.value = undefined
    editing.value = undefined
  }
  error.value = ''
  loadingTasks.value = true
  try {
    const result = await workList<JobRow>('task-admin', {
      warehouseId: props.warehouse,
      type: kind.value || undefined,
      status: status.value || undefined,
      assignee: mine.value ? 'me' : undefined,
      cursor,
    })
    if (request !== taskRequest) return
    tasks.value = append ? [...tasks.value, ...result.items] : result.items
    taskCursor.value = result.nextCursor ?? undefined
  } catch (cause: unknown) {
    if (request === taskRequest) error.value = messageOf(cause)
  } finally {
    if (request === taskRequest) loadingTasks.value = false
  }
}

async function prepareAssign(row: JobRow): Promise<void> {
  editing.value = row
  operator.value = ''
  assignees.value = []
  await searchAssignees('')
}

async function searchAssignees(keyword: string): Promise<void> {
  const taskId = editing.value?.id
  if (!taskId) return
  const request = ++assigneeRequest
  searchingAssignees.value = true
  try {
    const result = await taskAssignees(taskId, keyword.trim() || undefined)
    if (request === assigneeRequest && editing.value?.id === taskId) assignees.value = result.items
  } catch (cause: unknown) {
    if (request === assigneeRequest && editing.value?.id === taskId) error.value = messageOf(cause)
  } finally {
    if (request === assigneeRequest) searchingAssignees.value = false
  }
}

async function transition(row: JobRow, action: 'assign' | 'cancel'): Promise<void> {
  const data = {
    version: row.version,
    assigneeId: action === 'assign' ? operator.value : undefined,
  }
  const path = '/task-admin/' + row.id + '/' + action
  if (
    await run(
      path,
      data,
      async (key) => {
        await workCommand(path, data, key)
        return true
      },
      action === 'assign' ? '作业已分配。' : '作业已取消。',
    )
  ) {
    editing.value = undefined
    await load()
  }
}

async function statistics(): Promise<void> {
  try {
    stats.value = (
      await workStatistics(props.warehouse, begin.value || undefined, end.value || undefined)
    ).items
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}

function editRule(row?: LocationRule): void {
  skuScan.value = ''
  rule.value = row ? { ...row } : { warehouseId: props.warehouse }
}

async function saveRule(): Promise<void> {
  const input = rule.value
  if (!input) return
  const payload: LocationRule = {
    id: input.id,
    version: input.version,
    warehouseId: props.warehouse,
    skuId: input.skuId || undefined,
    categoryId: input.categoryId || undefined,
    zoneId: input.zoneId || undefined,
    fixedLocationId: input.fixedLocationId || undefined,
    minQty: input.minQty || undefined,
    maxQty: input.maxQty || undefined,
  }
  const result = await run(
    'location-rule',
    payload,
    async () => {
      if (skuScan.value.trim()) {
        const item = await scanCode(skuScan.value.trim(), props.warehouse)
        if (item.type !== 'sku') throw new ApiError(422, 'VALIDATION_FAILED', '请使用商品条码。')
        payload.skuId = item.id
        payload.categoryId = undefined
      }
      return saveLocationRule(payload)
    },
    '库位规则已保存。',
  )
  if (result) {
    rule.value = undefined
    await load()
  }
}

async function addByScan(): Promise<void> {
  if (!labelScan.value.trim()) return
  const result = await run('label-scan', labelScan.value, () =>
    scanCode(labelScan.value.trim(), props.warehouse),
  )
  if (!result) return
  if (result.type === 'serial') {
    error.value = '请选择要打印的商品、库位、批次或箱。'
    return
  }
  queue.value.push({ ...result, copies: 1 })
  labelScan.value = ''
}

function addById(): void {
  if (!/^[1-9][0-9]{0,18}$/.test(labelId.value.trim())) {
    error.value = '请输入有效的对象编号。'
    return
  }
  const id = labelId.value.trim()
  queue.value.push({
    id,
    type: labelType.value,
    code: id,
    name: labels[labelType.value] ?? '标签',
    copies: 1,
  })
  labelId.value = ''
  error.value = ''
}

async function print(): Promise<void> {
  const items = queue.value.map(({ id, type, copies }) => ({ id, type, copies }))
  const file = await run('label-print', { items, size: size.value, format: format.value }, () =>
    createLabels(props.warehouse, size.value, format.value, items),
  )
  if (!file) return
  const address = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = address
  link.download = '仓储标签.' + format.value
  link.click()
  setTimeout(() => URL.revokeObjectURL(address), 1000)
}
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="notice" role="status">{{ notice }}</p>
  <template v-if="area === 'tasks'">
    <div class="setting-row">
      <el-select
        v-model="kind"
        clearable
        placeholder="全部类型"
        aria-label="作业类型"
        @change="load"
      >
        <el-option v-for="(title, value) in types" :key="value" :value="value" :label="title" />
      </el-select>
      <el-select
        v-model="status"
        clearable
        placeholder="全部状态"
        aria-label="作业状态"
        @change="load"
      >
        <el-option v-for="(title, value) in states" :key="value" :value="value" :label="title" />
      </el-select>
      <el-checkbox v-model="mine" @change="load">只看我的作业</el-checkbox>
      <el-button :loading="loadingTasks" @click="load">刷新任务</el-button>
    </div>
    <el-table v-loading="loadingTasks" :data="tasks" empty-text="暂无仓储任务">
      <el-table-column prop="no" label="任务号" />
      <el-table-column label="类型"
        ><template #default="{ row }">{{ types[row.type] ?? row.type }}</template></el-table-column
      >
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          states[row.status] ?? row.status
        }}</template></el-table-column
      >
      <el-table-column prop="assigneeName" label="处理人" />
      <el-table-column v-if="session.can('wms_task:assign')" label="操作" width="200">
        <template #default="{ row }">
          <el-button
            v-if="!row.waveId && ['created', 'assigned'].includes(row.status)"
            link
            type="primary"
            @click="prepareAssign(row)"
            >分配作业</el-button
          >
          <el-button
            v-if="row.type !== 'pick' && ['created', 'assigned'].includes(row.status)"
            link
            type="danger"
            @click="transition(row, 'cancel')"
            >取消任务</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="taskCursor" :loading="loadingTasks" @click="loadTasks(true)">
      加载更多作业
    </el-button>
    <div v-if="editing" class="setting-row">
      <label>{{ editing.no }}</label>
      <el-select
        v-model="operator"
        aria-label="处理人"
        placeholder="选择当前范围的作业员"
        filterable
        remote
        :remote-method="searchAssignees"
        :loading="searchingAssignees"
      >
        <el-option
          v-for="user in assignees"
          :key="user.id"
          :value="user.id"
          :label="user.displayName"
        />
      </el-select>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="!operator"
        @click="transition(editing, 'assign')"
        >确认分配</el-button
      >
      <el-button @click="editing = undefined">关闭</el-button>
    </div>
    <h3>人员作业量</h3>
    <div class="setting-row">
      <el-input v-model="begin" type="date" aria-label="统计起始日" />
      <el-input v-model="end" type="date" aria-label="统计截止日" />
      <el-button @click="statistics">查询统计</el-button>
    </div>
    <el-table :data="stats" empty-text="暂无统计记录">
      <el-table-column prop="assigneeName" label="作业员" />
      <el-table-column label="类型"
        ><template #default="{ row }">{{ types[row.type] ?? row.type }}</template></el-table-column
      >
      <el-table-column prop="taskCount" label="完成数" />
      <el-table-column prop="averageSeconds" label="平均耗时（秒）" />
    </el-table>
  </template>
  <template v-else-if="area === 'rules'">
    <el-button v-if="session.can('warehouse:update')" type="primary" @click="editRule()"
      >新增库位规则</el-button
    >
    <el-table :data="rules" empty-text="暂无库位规则">
      <el-table-column prop="skuCode" label="商品" />
      <el-table-column prop="categoryName" label="分类" />
      <el-table-column prop="zoneName" label="优先库区" />
      <el-table-column prop="fixedLocationCode" label="固定库位" />
      <el-table-column prop="minQty" label="补货下限" />
      <el-table-column prop="maxQty" label="补货上限" />
      <el-table-column v-if="session.can('warehouse:update')" label="操作"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="editRule(row)">编辑</el-button></template
        ></el-table-column
      >
    </el-table>
    <el-form v-if="rule" label-position="top" @submit.prevent="saveRule">
      <div class="setting-row">
        <el-form-item label="商品条码或编码"><el-input v-model="skuScan" /></el-form-item>
        <el-form-item label="商品编号"><el-input v-model="rule.skuId" /></el-form-item>
        <el-form-item label="分类编号"><el-input v-model="rule.categoryId" /></el-form-item>
      </div>
      <div class="setting-row">
        <el-form-item label="优先库区">
          <el-select v-if="layout" v-model="rule.zoneId" clearable aria-label="优先库区">
            <el-option
              v-for="item in layout.zones.filter(
                (row) => row.type === 'storage' && row.status === 'active',
              )"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
          <el-input v-else v-model="rule.zoneId" placeholder="库区编号" />
        </el-form-item>
        <el-form-item label="固定库位">
          <el-select v-if="layout" v-model="rule.fixedLocationId" clearable aria-label="固定库位">
            <el-option
              v-for="item in layout.locations.filter((row) => row.status === 'active')"
              :key="item.id"
              :value="item.id"
              :label="item.code + ' ' + item.name"
            />
          </el-select>
          <el-input v-else v-model="rule.fixedLocationId" placeholder="库位编号" />
        </el-form-item>
        <el-form-item label="补货下限"><el-input v-model="rule.minQty" /></el-form-item>
        <el-form-item label="补货上限"><el-input v-model="rule.maxQty" /></el-form-item>
      </div>
      <el-button type="primary" native-type="submit" :loading="busy">保存规则</el-button>
      <el-button @click="rule = undefined">关闭</el-button>
    </el-form>
  </template>
  <template v-else>
    <div class="setting-row">
      <el-input
        v-model="labelScan"
        aria-label="扫码加入标签"
        placeholder="扫描商品、批次、库位或箱码"
        @keydown.enter.prevent="addByScan"
      />
      <el-button type="primary" :loading="busy" @click="addByScan">扫码加入</el-button>
      <el-select v-model="labelType" aria-label="标签类型"
        ><el-option v-for="(title, value) in labels" :key="value" :value="value" :label="title"
      /></el-select>
      <el-input v-model="labelId" aria-label="标签对象编号" placeholder="输入对象编号" />
      <el-button @click="addById">按编号加入</el-button>
    </div>
    <el-table :data="queue" empty-text="请扫码或输入对象编号添加标签">
      <el-table-column label="类型"
        ><template #default="{ row }">{{ labels[row.type] }}</template></el-table-column
      >
      <el-table-column prop="name" label="对象" />
      <el-table-column prop="code" label="编码" />
      <el-table-column label="份数"
        ><template #default="{ row }"
          ><el-input-number v-model="row.copies" :min="1" :max="100" aria-label="份数" /></template
      ></el-table-column>
      <el-table-column label="操作"
        ><template #default="{ $index }"
          ><el-button link type="danger" @click="queue.splice($index, 1)">移除</el-button></template
        ></el-table-column
      >
    </el-table>
    <div class="setting-row">
      <el-select v-model="size" aria-label="纸张尺寸">
        <el-option
          v-for="value in ['50x30', '70x40', '100x100']"
          :key="value"
          :value="value"
          :label="value + ' mm'"
        />
      </el-select>
      <el-select v-model="format" aria-label="打印格式">
        <el-option
          v-for="value in ['pdf', 'zpl', 'tspl']"
          :key="value"
          :value="value"
          :label="value.toUpperCase()"
        />
      </el-select>
      <el-button type="primary" :disabled="!queue.length" :loading="busy" @click="print"
        >下载打印文件</el-button
      >
    </div>
  </template>
</template>

<style scoped>
.setting-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  margin: 12px 0;
}
.setting-row > .el-input,
.setting-row > .el-select,
.setting-row .el-form-item {
  width: 180px;
}
</style>
