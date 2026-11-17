<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { codeRules, previewCodeRule, saveCodeRule } from '../api/platform-rules'
import type { CodeRule } from '../api/platform-rules'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const targets: Record<string, string> = {
  'document:sales_order': '销售订单',
  'document:purchase_order': '采购订单',
  'document:shipment': '发货单',
  'document:goods_receipt': '收货单',
  'document:purchase_requisition': '请购单',
  'document:purchase_return': '采购退货',
  'document:receipt': '收款单',
  'document:payment': '付款单',
  'document:account_transfer': '账户转账',
  'document:stock_adjustment': '库存调整',
  'document:stock_count': '盘点单',
  'document:stock_transfer': '调拨单',
  'document:opening_entry': '期初单',
  'document:document_reversal': '冲销单',
  'document:refund_request': '退款申请',
  'document:sales_return': '销售退货',
  'masterdata:sku': '商品 SKU',
  'masterdata:partner': '往来单位',
}
const segmentTypes: Record<string, string> = {
  literal: '固定字符',
  company_code: '公司编码',
  date: '日期',
  dept_code: '部门编码',
  category_code: '物料分类',
  serial: '流水号',
}
const cycles: Record<string, string> = { none: '不重置', year: '按年', month: '按月', day: '按日' }
const rows = ref<CodeRule[]>([]),
  dialog = ref(false),
  busy = ref(false),
  error = ref(''),
  formError = ref('')
const display = ref(''),
  previewError = ref(''),
  previewDate = ref(''),
  categoryId = ref('')
const form = reactive<CodeRule>({
  companyId: '0',
  target: 'document:sales_order',
  segments: [],
  resetCycle: 'month',
  enabled: true,
})
const dragIndex = ref(-1)

async function load(): Promise<void> {
  const company = session.companyId
  rows.value = []
  error.value = ''
  try {
    const result = await codeRules()
    if (company === session.companyId) rows.value = result
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
function edit(rule?: CodeRule): void {
  Object.assign(
    form,
    rule
      ? { ...rule, segments: rule.segments.map((item) => ({ ...item })) }
      : {
          id: undefined,
          version: undefined,
          companyId: session.companyId ?? '0',
          target: 'document:sales_order',
          segments: [
            { type: 'literal', value: 'SO-' },
            { type: 'date', value: 'yyyyMM' },
            { type: 'serial', length: 6 },
          ],
          resetCycle: 'month',
          enabled: true,
        },
  )
  formError.value = ''
  previewDate.value = ''
  categoryId.value = ''
  dialog.value = true
}
function changeType(index: number, type: string): void {
  const segment = form.segments[index]
  if (!segment) return
  segment.type = type
  segment.value = type === 'date' ? 'yyyyMM' : type === 'literal' ? '-' : undefined
  segment.length =
    type === 'serial'
      ? 6
      : type === 'company_code'
        ? 16
        : ['category_code', 'dept_code'].includes(type)
          ? 10
          : undefined
}
function move(index: number, to: number): void {
  if (
    index < 0 ||
    index >= form.segments.length ||
    to < 0 ||
    to >= form.segments.length ||
    to === index
  )
    return
  const [segment] = form.segments.splice(index, 1)
  if (segment) form.segments.splice(to, 0, segment)
}
function drop(to: number): void {
  move(dragIndex.value, to)
  dragIndex.value = -1
}
async function save(): Promise<void> {
  if (busy.value) return
  busy.value = true
  formError.value = ''
  try {
    await saveCodeRule({ ...form })
    dialog.value = false
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) formError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
watch(
  () => session.companyId,
  () => {
    dialog.value = false
    void load()
  },
  { immediate: true },
)
watch(
  () => form.target,
  (value) => {
    if (value.startsWith('masterdata:')) form.companyId = '0'
  },
)
watch([() => JSON.stringify(form), dialog, previewDate, categoryId], (_, __, onCleanup) => {
  display.value = ''
  previewError.value = ''
  if (!dialog.value) return
  let active = true
  const timer = window.setTimeout(() => {
    void previewCodeRule(
      { ...form },
      previewDate.value || undefined,
      categoryId.value.trim() || undefined,
    )
      .then((result) => {
        if (active) display.value = result.code
      })
      .catch((cause: unknown) => {
        if (active && !isCancelled(cause)) previewError.value = messageOf(cause)
      })
  }, 350)
  onCleanup(() => {
    active = false
    window.clearTimeout(timer)
  })
})
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>编码规则</h1>
      <p class="muted">配置新单据和主数据的自动编号。</p>
    </div>
    <el-button type="primary" @click="edit()">新增规则</el-button>
  </div>
  <section class="panel">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" empty-text="暂无编码规则，单据使用默认编号" row-key="id">
      <el-table-column label="业务类型"
        ><template #default="{ row }">{{
          targets[row.target as string]
        }}</template></el-table-column
      >
      <el-table-column label="适用范围"
        ><template #default="{ row }">{{
          row.companyId === '0' ? '全公司通用' : session.company?.name
        }}</template></el-table-column
      >
      <el-table-column label="重置周期"
        ><template #default="{ row }">{{
          cycles[row.resetCycle as string]
        }}</template></el-table-column
      >
      <el-table-column label="状态"
        ><template #default="{ row }">{{
          row.enabled ? '启用' : '停用'
        }}</template></el-table-column
      >
      <el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row as CodeRule)">编辑</el-button></template
        ></el-table-column
      >
    </el-table>
  </section>
  <el-dialog
    v-model="dialog"
    :title="form.id ? '编辑规则' : '新增规则'"
    width="min(780px,calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <el-form label-position="top" @submit.prevent="save">
      <div class="rule-grid">
        <el-form-item label="业务类型"
          ><el-select v-model="form.target" :disabled="!!form.id"
            ><el-option
              v-for="(name, key) in targets"
              :key="key"
              :value="key"
              :label="name" /></el-select
        ></el-form-item>
        <el-form-item label="适用范围"
          ><el-select
            v-model="form.companyId"
            :disabled="!!form.id || form.target.startsWith('masterdata:')"
          >
            <el-option label="全公司通用" value="0" /><el-option
              v-if="session.company"
              :label="session.company.name"
              :value="session.company.id"
            /> </el-select
        ></el-form-item>
        <el-form-item label="流水重置"
          ><el-select v-model="form.resetCycle"
            ><el-option
              v-for="(label, key) in cycles"
              :key="key"
              :value="key"
              :label="label" /></el-select
        ></el-form-item>
      </div>
      <div
        v-for="(item, index) in form.segments"
        :key="index"
        class="rule-segment"
        draggable="true"
        @dragstart="dragIndex = index"
        @dragover.prevent
        @drop.prevent="drop(index)"
      >
        <el-select
          :model-value="item.type"
          :aria-label="'第 ' + (index + 1) + ' 段类型'"
          @change="changeType(index, $event)"
          ><el-option v-for="(name, key) in segmentTypes" :key="key" :value="key" :label="name"
        /></el-select>
        <el-input
          v-if="item.type === 'literal'"
          v-model="item.value"
          maxlength="16"
          aria-label="固定字符"
        />
        <el-select v-else-if="item.type === 'date'" v-model="item.value" aria-label="日期格式"
          ><el-option
            v-for="value in ['yyyy', 'yyyyMM', 'yyyyMMdd', 'yyMMdd']"
            :key="value"
            :value="value"
            :label="value"
        /></el-select>
        <el-input-number
          v-else
          v-model="item.length"
          :min="1"
          :max="item.type === 'serial' ? 10 : item.type === 'company_code' ? 16 : 32"
          aria-label="长度上限"
        />
        <div>
          <el-button size="small" :disabled="index === 0" @click="move(index, index - 1)"
            >上移</el-button
          ><el-button
            size="small"
            :disabled="index === form.segments.length - 1"
            @click="move(index, index + 1)"
            >下移</el-button
          ><el-button size="small" @click="form.segments.splice(index, 1)">删除</el-button>
        </div>
      </div>
      <el-button
        :disabled="form.segments.length >= 10"
        @click="form.segments.push({ type: 'literal', value: '-' })"
        >添加段</el-button
      >
      <el-checkbox v-model="form.enabled">启用规则</el-checkbox>
      <div class="rule-grid rule-preview">
        <el-form-item label="预览业务日期"
          ><el-input v-model="previewDate" type="date"
        /></el-form-item>
        <el-form-item
          v-if="form.segments.some((item) => item.type === 'category_code')"
          label="预览分类编号"
          ><el-input v-model="categoryId"
        /></el-form-item>
        <div>
          <p class="muted">下一个编号</p>
          <strong v-if="display">{{ display }}</strong><span v-else class="muted">{{
            previewError || '正在预览…'
          }}</span>
        </div>
      </div>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
    </el-form>
    <template #footer
      ><el-button @click="dialog = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.rule-grid,
.rule-segment {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  align-items: center;
}
.rule-segment {
  margin-bottom: 10px;
  grid-template-columns: 1fr 1fr auto;
}
.rule-preview {
  margin-top: 14px;
}
.el-select {
  width: 100%;
}
.el-checkbox {
  margin-left: 12px;
}
@media (max-width: 650px) {
  .rule-grid,
  .rule-segment {
    grid-template-columns: 1fr;
  }
}
</style>
