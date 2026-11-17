<script setup lang="ts">
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import { computed, ref, watch, onScopeDispose } from 'vue'
import { glConfig, glCommand, glSubjects, glVouchers, glVoucher } from '../../api/gl'
import { messageOf, isCancelled, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { auxLabels } from '../../types/gl'
import type { GlConfigRow, GlSubject, GlLine, GlVoucher } from '../../types/gl'
import type { FormField, SelectOption } from '../../types/admin'
import DataFields from '../../components/DataFields.vue'
import RecordFrame from '../../components/RecordFrame.vue'
const session = useSessionStore(),
  kind = ref('subjects'),
  rows = ref<GlConfigRow[]>([]),
  subjects = ref<GlSubject[]>([]),
  auxTypes = ref<GlConfigRow[]>([]),
  cash = ref<GlConfigRow[]>([]),
  words = ref<GlConfigRow[]>([]),
  voucherRows = ref<GlVoucher[]>([])
const selected = ref<GlConfigRow>(),
  open = ref(false),
  busy = ref(false),
  error = ref(''),
  values = ref<Record<string, unknown>>({}),
  voucherId = ref('')
const kinds: Record<string, string> = {
  subjects: '会计科目',
  'aux-types': '辅助类型',
  'aux-items': '辅助项目',
  'cash-flow-items': '现金流量',
  mappings: '业务映射',
  words: '凭证字',
  digests: '摘要库',
  'voucher-templates': '凭证模板',
  'closing-schemes': '结转方案',
  'report-templates': '报表公式',
  'export-formats': '导出列映射',
}
const options = (pairs: string[][]): SelectOption[] =>
  pairs.map(([value, label]) => ({ value: value!, label: label! }))
const text = (key: string, label: string): FormField => ({ key, label, required: true, max: 100 })
const select = (key: string, label: string, choices: string[][]): FormField => ({
  key,
  label,
  kind: 'select',
  options: options(choices),
  required: true,
})
const status: FormField = {
  ...select('status', '状态', [
    ['active', '启用'],
    ['disabled', '停用'],
  ]),
  initial: 'active',
}
const base = [text('code', '编码'), text('name', '名称')]
const fields = computed<FormField[]>(() => {
  switch (kind.value) {
    case 'subjects':
      return [
        ...base,
        { key: 'parentId', label: '上级科目', kind: 'select', emptyAsNull: true },
        select('category', '类别', [
          ['asset', '资产'],
          ['liability', '负债'],
          ['equity', '权益'],
          ['cost', '成本'],
          ['income_expense', '损益'],
          ['common', '共同'],
        ]),
        select('direction', '余额方向', [
          ['D', '借方'],
          ['C', '贷方'],
        ]),
        { key: 'isCash', label: '现金科目', kind: 'boolean' },
        { key: 'isBank', label: '银行科目', kind: 'boolean' },
        {
          key: 'auxTypes',
          label: '辅助核算',
          kind: 'multiple',
          options: options(Object.entries(auxLabels)),
        },
        { key: 'customAuxTypeId', label: '自定义辅助类型', kind: 'select', emptyAsNull: true },
        { key: 'quantityUnit', label: '数量单位', max: 20 },
        {
          ...select('profitRole', '损益归属', [
            ['none', '非损益'],
            ['current', '本期损益'],
            ['prior', '以前年度损益'],
          ]),
          initial: 'none',
        },
        status,
      ]
    case 'aux-types':
      return [...base, select('source', '数据来源', Object.entries(auxLabels)), status]
    case 'aux-items':
      return [
        ...base,
        { key: 'auxTypeId', label: '辅助类型', kind: 'select', required: true },
        status,
      ]
    case 'cash-flow-items':
      return [
        ...base,
        select('direction', '流量方向', [
          ['in', '流入'],
          ['out', '流出'],
          ['neutral', '内部互转'],
        ]),
        select('category', '活动类别', [
          ['operating', '经营'],
          ['investing', '投资'],
          ['financing', '筹资'],
          ['internal', '内部'],
        ]),
        { key: 'sortNo', label: '排序', kind: 'number', initial: 0 },
        status,
      ]
    case 'words':
      return [
        text('code', '凭证字'),
        text('printTitle', '打印标题'),
        { key: 'isDefault', label: '默认凭证字', kind: 'boolean' },
        status,
      ]
    case 'digests':
      return [text('content', '摘要'), status]
    case 'mappings':
      return [
        text('ledgerAccountCode', '运营账户代码'),
        { key: 'subjectId', label: '会计科目', kind: 'select', required: true },
        { key: 'cashFlowItemId', label: '现金流量项目', kind: 'select', emptyAsNull: true },
        { key: 'priority', label: '优先级（同账户不能重复）', kind: 'number', initial: 1 },
        { key: 'enabled', label: '启用映射', kind: 'boolean', initial: true },
      ]
    case 'voucher-templates':
      return [...base, { key: 'wordId', label: '凭证字', kind: 'select', required: true }, status]
    case 'closing-schemes':
      return [
        ...base,
        { key: 'sortNo', label: '顺序', kind: 'number', initial: 100 },
        {
          key: 'sourceCodes',
          label: '损益科目编码（@current_profit 表示本期损益）',
          kind: 'tags',
          required: true,
        },
        { key: 'targetSubjectId', label: '结转目标', kind: 'select', required: true },
        { key: 'enabled', label: '启用方案', kind: 'boolean', initial: true },
      ]
    case 'report-templates':
      return [text('name', '报表名称')]
    case 'export-formats':
      return [
        ...base,
        select('fileType', '文件格式', [
          ['csv', 'CSV'],
          ['xlsx', 'Excel'],
        ]),
      ]
    default:
      return []
  }
})
const choices = computed<Record<string, SelectOption[]>>(() => {
  const subjectOptions = subjects.value.map((s) => ({ value: s.id, label: s.code + ' ' + s.name }))
  return {
    parentId: subjectOptions,
    subjectId: subjectOptions.filter((o) => subjects.value.find((s) => s.id === o.value)?.isLeaf),
    targetSubjectId: subjectOptions,
    auxTypeId: auxTypes.value.map((r) => ({ value: r.id, label: r.name || r.code || r.id })),
    customAuxTypeId: auxTypes.value
      .filter((r) => r.source === 'custom')
      .map((r) => ({ value: r.id, label: r.name || r.id })),
    cashFlowItemId: cash.value.map((r) => ({ value: r.id, label: r.name || r.id })),
    wordId: words.value.map((r) => ({ value: r.id, label: r.code || r.id })),
  }
})
interface Pair {
  key: string
  value: string
}
interface FormulaRow {
  code: string
  name: string
  formula: string
  bold: boolean
  indent: number
}
interface FormulaCheck {
  name: string
  left: string
  right: string
}
interface Column {
  field: string
  label: string
}
const conditions = ref<Pair[]>([]),
  rules = ref<Pair[]>([]),
  formulaRows = ref<FormulaRow[]>([]),
  checks = ref<FormulaCheck[]>([]),
  columns = ref<Column[]>([]),
  templateLines = ref<GlLine[]>([])
const columnFields = [
  'date',
  'word',
  'number',
  'lineNo',
  'digest',
  'subjectCode',
  'subjectName',
  'direction',
  'amount',
  'debit',
  'credit',
  'quantity',
  'unitPrice',
  'customerId',
  'supplierId',
  'deptId',
  'employeeId',
  'projectId',
  'skuId',
  'customId',
  'cashFlowCode',
  'sourceNo',
]
function parsed<T>(value: unknown, fallback: T): T {
  return value == null ? fallback : (JSON.parse(String(value)) as T)
}
let revision = 0,
  saveKey = newIdempotencyKey()
async function load(): Promise<void> {
  const current = ++revision
  rows.value = []
  subjects.value = []
  auxTypes.value = []
  cash.value = []
  words.value = []
  error.value = ''
  busy.value = true
  try {
    const [data, s, a, c, w] = await Promise.all([
      glConfig(kind.value),
      glSubjects(),
      glConfig('aux-types'),
      glConfig('cash-flow-items'),
      glConfig('words'),
    ])
    if (current !== revision) return
    rows.value = data
    subjects.value = s
    auxTypes.value = a
    cash.value = c
    words.value = w
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function edit(row?: GlConfigRow): Promise<void> {
  selected.value = row
  saveKey = newIdempotencyKey()
  values.value = Object.fromEntries(
    fields.value.map((f) => [
      f.key,
      row?.[f.key] ??
        f.initial ??
        (f.kind === 'boolean' ? false : f.kind === 'multiple' || f.kind === 'tags' ? [] : ''),
    ]),
  )
  for (const f of fields.value)
    if (f.kind === 'boolean') values.value[f.key] = !!values.value[f.key]
  if (row && kind.value === 'subjects') values.value.auxTypes = parsed(row.auxTypes, [])
  if (row && kind.value === 'closing-schemes')
    values.value.sourceCodes = parsed(row.sourceCodes, [])
  conditions.value = Object.entries(parsed<Record<string, string>>(row?.conditionJson, {})).map(
    ([key, value]) => ({ key, value }),
  )
  rules.value = Object.entries(parsed<Record<string, string>>(row?.auxRules, {})).map(
    ([key, value]) => ({ key, value }),
  )
  formulaRows.value = parsed(row?.rowsJson, [])
  checks.value = parsed(row?.checksJson, [])
  columns.value = parsed(row?.columnsJson, [])
  templateLines.value = parsed(row?.linesJson, [])
  voucherId.value = ''
  open.value = true
  if (kind.value === 'voucher-templates' && session.can('gl_voucher:view'))
    try {
      voucherRows.value = await glVouchers({ limit: 100 })
    } catch (e: unknown) {
      if (!isCancelled(e)) error.value = messageOf(e)
    }
}
async function chooseVoucher(): Promise<void> {
  try {
    const v = await glVoucher(voucherId.value)
    templateLines.value = v.lines.map((l) => ({
      ...l,
      aux: Object.fromEntries(
        Object.keys(auxLabels).map((key) => [
          key + 'Id',
          l[('aux' + key[0]!.toUpperCase() + key.slice(1) + 'Id') as keyof GlLine] || '0',
        ]),
      ),
    }))
    values.value.wordId = v.wordId
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  }
}
async function save(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const body: Record<string, unknown> = { ...values.value, version: selected.value?.version ?? 0 }
    for (const field of fields.value)
      if (field.emptyAsNull && !body[field.key]) body[field.key] = null
    if (kind.value === 'mappings') {
      body.condition = Object.fromEntries(conditions.value.map((p) => [p.key, p.value]))
      body.auxRules = Object.fromEntries(rules.value.map((p) => [p.key, p.value]))
    }
    if (kind.value === 'report-templates') {
      body.rows = formulaRows.value
      body.checks = checks.value
    }
    if (kind.value === 'export-formats') body.columns = columns.value
    if (kind.value === 'voucher-templates') body.lines = templateLines.value
    await glCommand(
      'config/' + kind.value + (selected.value ? '/' + selected.value.id : ''),
      body,
      saveKey,
      selected.value ? 'PUT' : 'POST',
    )
    open.value = false
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
watch(
  [() => session.companyId, kind],
  () => {
    open.value = false
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>财务基础配置</h1>
      <p>配置独立属于当前公司。改动后需重新确认财务口径，历史凭证保持原核算快照。</p>
    </div>
    <el-button
      v-if="session.can('gl_config:edit') && kind !== 'report-templates'"
      type="primary"
      @click="edit()"
      >新增{{ kinds[kind] }}</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel config-panel">
    <el-tabs v-model="kind"
      ><el-tab-pane v-for="(title, key) in kinds" :key="key" :label="title" :name="key" /></el-tabs
    ><el-table :data="rows"
      ><el-table-column prop="code" label="编码" min-width="130" /><el-table-column
        label="名称 / 摘要"
        min-width="220"
        ><template #default="{ row }">{{
          row.name || row.content || row.printTitle || row.ledgerAccountCode
        }}</template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }">{{
          row.status === 'active' ? '启用' : row.status === 'disabled' ? '停用' : '—'
        }}</template></el-table-column
      ><el-table-column label="操作" width="100"
        ><template #default="{ row }"
          ><el-button v-if="session.can('gl_config:edit')" link @click="edit(row)"
            >编辑</el-button
          ></template
        ></el-table-column
      ><template #empty>{{
        busy ? '正在读取配置…' : '暂无配置，请先创建账套或添加配置'
      }}</template></el-table
    >
  </section>
  <RecordFrame
    v-if="open"
    :title="(selected ? '编辑' : '新增') + kinds[kind]"
    width="960px"
    :busy="busy"
    @close="open = false"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" /><DataFields
      :fields="fields"
      :values="values"
      :options="choices"
      :editing="!!selected"
      :disabled="busy"
      @change="(key, value) => (values[key] = value)"
    />
    <template v-if="kind === 'mappings'"
      ><h3>匹配条件（全部满足时生效）</h3>
      <div v-for="(p, i) in conditions" :key="i" class="pair">
        <el-select v-model="p.key"
          ><el-option
            v-for="field in [
              'sourceType',
              'cashAccountId',
              'cashAccountType',
              'receiptType',
              'incomeExpenseItemId',
              'paymentType',
              'partnerId',
              'skuId',
              'warehouseId',
            ]"
            :key="field"
            :value="field"
            :label="field" /></el-select
        ><el-input v-model="p.value" placeholder="匹配值" /><el-button
          link
          @click="conditions.splice(i, 1)"
          >移除</el-button
        >
      </div>
      <el-button @click="conditions.push({ key: 'sourceType', value: '' })">添加条件</el-button>
      <h3>辅助取值</h3>
      <p>来源可选 partnerId、skuId、deptId、businessUserId；也可填写固定辅助项目编号。</p>
      <div v-for="(p, i) in rules" :key="i" class="pair">
        <el-select v-model="p.key"
          ><el-option
            v-for="(label, key) in auxLabels"
            :key="key"
            :value="key"
            :label="label" /></el-select
        ><el-input v-model="p.value" placeholder="来源字段或固定编号" /><el-button
          link
          @click="rules.splice(i, 1)"
          >移除</el-button
        >
      </div>
      <el-button @click="rules.push({ key: 'customer', value: 'partnerId' })"
        >添加辅助规则</el-button
      ></template
    >
    <template v-if="kind === 'report-templates'"
      ><p>
        支持 QC、QM、JF、DF、LJ、XJ、R、CAT 等取数函数；R 引用行编码。服务器拒绝循环引用和脚本。
      </p>
      <el-table :data="formulaRows"
        ><el-table-column label="行编码" width="150"
          ><template #default="{ row }"><el-input v-model="row.code" /></template></el-table-column
        ><el-table-column label="项目" width="180"
          ><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column
        ><el-table-column label="公式" min-width="300"
          ><template #default="{ row }"
            ><el-input v-model="row.formula" /></template></el-table-column
        ><el-table-column label="汇总" width="70"
          ><template #default="{ row }"
            ><el-checkbox v-model="row.bold" /></template></el-table-column
        ><el-table-column width="70"
          ><template #default="{ $index }"
            ><el-button link @click="formulaRows.splice($index, 1)">移除</el-button></template
          ></el-table-column
        ></el-table
      ><el-button
        @click="formulaRows.push({ code: '', name: '', formula: '0', bold: false, indent: 0 })"
        >添加报表行</el-button
      >
      <h3>勾稽校验</h3>
      <div v-for="(c, i) in checks" :key="i" class="pair">
        <el-input v-model="c.name" placeholder="校验名称" /><el-input
          v-model="c.left"
          placeholder="左侧公式"
        /><el-input v-model="c.right" placeholder="右侧公式" /><el-button
          link
          @click="checks.splice(i, 1)"
          >移除</el-button
        >
      </div>
      <el-button @click="checks.push({ name: '', left: '0', right: '0' })"
        >添加校验</el-button
      ></template
    >
    <template v-if="kind === 'export-formats'"
      ><el-alert
        title="用友、金蝶格式为可调整列映射样例，请在目标软件版本试导入后确认。"
        type="info"
        :closable="false"
      />
      <div v-for="(c, i) in columns" :key="i" class="pair">
        <el-select v-model="c.field"
          ><el-option v-for="f in columnFields" :key="f" :value="f" :label="f" /></el-select
        ><el-input v-model="c.label" placeholder="列标题" /><el-button
          link
          @click="columns.splice(i, 1)"
          >移除</el-button
        >
      </div>
      <el-button @click="columns.push({ field: 'date', label: '凭证日期' })"
        >添加列</el-button
      ></template
    >
    <template v-if="kind === 'voucher-templates'"
      ><h3>从现有凭证复制分录结构</h3>
      <el-select v-model="voucherId" placeholder="选择凭证" @change="chooseVoucher"
        ><el-option
          v-for="v in voucherRows"
          :key="v.id"
          :value="v.id"
          :label="v.voucherDate + ' / ' + v.number"
      /></el-select>
      <p>
        当前模板 {{ templateLines.length }} 条分录，套用后仍须核对金额、辅助项目和现金流量。
      </p></template
    >
    <template #footer
      ><el-button :disabled="busy" @click="open = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存配置</el-button></template
    ></RecordFrame
  >
</template>
<style scoped>
.config-panel {
  padding: 18px;
}
.pair {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 10px 0;
}
.pair > .el-input,
.pair > .el-select {
  flex: 1;
  min-width: 0;
}
</style>
