<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { allRecords } from '../api/admin'
import { isCancelled, messageOf } from '../api/http'
import { qualitySettings, saveQualitySetting } from '../api/quality'
import type { QualityItem, QualityTemplate, QualityRule, QualitySettingPath, QualityCheck } from '../api/quality'
import { useSessionStore } from '../stores/session'
import type { DataRow } from '../types/admin'

const session = useSessionStore()
const tab = ref<QualitySettingPath>('items')
const items = ref<QualityItem[]>([]), templates = ref<QualityTemplate[]>([]), rules = ref<QualityRule[]>([])
const skus = ref<DataRow[]>([]), categories = ref<DataRow[]>([]), suppliers = ref<DataRow[]>([])
const kindNames = { items: '检验项目', templates: '检验模板', 'sku-rules': '检验规则' }
const typeLabels = { quantitative: '数值', qualitative: '定性' }
const samplingLabels = { full: '全检', fixed_qty: '固定数量', percent: '按比例' }
const busy = ref(false), error = ref(''), dialog = ref(false), loading = ref(false)
const edit = ref<QualityItem | QualityTemplate | QualityRule>()
const form = reactive({
  code: '', name: '', type: 'qualitative', unit: '', method: '', status: 'active', sampling: 'full',
  samplingValue: '', targetType: 'sku', targetId: '', supplierId: '', templateId: '', inspectionRequired: true,
  checks: [] as QualityCheck[],
})
const targets = computed(() => form.targetType === 'sku' ? skus.value : categories.value)
const manage = computed(() => session.can('qc_setting:manage'))
const current = computed(() => tab.value === 'items' ? items.value : tab.value === 'templates' ? templates.value : rules.value)
let revision = 0

function flatten(records: DataRow[]): DataRow[] {
  return records.flatMap(row => [row, ...flatten(row.children ?? [])])
}

async function load(append = false): Promise<void> {
  const sequence = ++revision, cursor = append ? current.value.at(-1)?.id : undefined
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'items') {
      const rows = await qualitySettings('items', cursor)
      if (sequence === revision) items.value = append ? [...items.value, ...rows] : rows
    } else if (tab.value === 'templates') {
      const rows = await qualitySettings('templates', cursor)
      if (sequence === revision) templates.value = append ? [...templates.value, ...rows] : rows
    } else {
      const rows = await qualitySettings('sku-rules', cursor)
      if (sequence === revision) rules.value = append ? [...rules.value, ...rows] : rows
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause) && sequence === revision) error.value = messageOf(cause)
  } finally {
    if (sequence === revision) loading.value = false
  }
}

function addCheck(): void {
  form.checks.push({ itemId: '', standardValue: '', lowerLimit: '', upperLimit: '', required: true })
}

async function open(row?: QualityItem | QualityTemplate | QualityRule): Promise<void> {
  edit.value = row
  Object.assign(form, {
    code: row && 'code' in row ? row.code : '',
    name: row && 'name' in row ? row.name : '',
    type: row && 'type' in row ? row.type : 'qualitative',
    unit: row && 'unit' in row ? row.unit ?? '' : '',
    method: row && 'method' in row ? row.method ?? '' : '',
    status: row?.status ?? 'active',
    sampling: row && 'sampling' in row ? row.sampling : 'full',
    samplingValue: row && 'samplingValue' in row ? row.samplingValue : '',
    checks: row && 'checks' in row ? row.checks.map(check => ({
      itemId: check.itemId, standardValue: check.standardValue, lowerLimit: check.lowerLimit, upperLimit: check.upperLimit, required: check.required,
    })) : [],
    targetType: row && 'targetType' in row ? row.targetType : 'sku',
    targetId: row && 'targetId' in row ? row.targetId : '',
    supplierId: row && 'supplierId' in row && row.supplierId !== '0' ? row.supplierId : '',
    templateId: row && 'templateId' in row ? row.templateId ?? '' : '',
    inspectionRequired: row && 'inspectionRequired' in row ? Boolean(row.inspectionRequired) : true,
  })
  dialog.value = true
  error.value = ''
  const requestTab = tab.value, company = session.companyId
  busy.value = true
  try {
    if (requestTab === 'templates') {
      const rows = await qualitySettings('items')
      if (company === session.companyId) items.value = rows
      if (!form.checks.length) addCheck()
    }
    if (requestTab === 'sku-rules') {
      const [skuRows, categoryRows, partnerRows, templateRows] = await Promise.all([
        session.can('sku:view') ? allRecords('skus') : [],
        session.can('product_category:view') ? allRecords('product-categories') : [],
        session.can('partner:view') ? allRecords('partners') : [],
        qualitySettings('templates'),
      ])
      if (company !== session.companyId) return
      skus.value = skuRows.filter(item => item.status === 'active')
      categories.value = flatten(categoryRows).filter(item => item.status === 'active')
      suppliers.value = partnerRows.filter(item => item.status === 'active')
      templates.value = templateRows
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function payload(): object {
  const header = { status: form.status, version: edit.value?.version }
  if (tab.value === 'items') return { ...header, code: form.code, name: form.name, type: form.type, unit: form.unit, method: form.method }
  if (tab.value === 'templates') return {
    ...header, code: form.code, name: form.name, sampling: form.sampling,
    samplingValue: form.samplingValue, checks: form.checks,
  }
  return {
    ...header, targetType: form.targetType, targetId: form.targetId, supplierId: form.supplierId,
    templateId: form.templateId, inspectionRequired: form.inspectionRequired,
  }
}

async function save(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await saveQualitySetting(tab.value, payload(), edit.value?.id)
    dialog.value = false
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

watch([tab, () => session.companyId], () => {
  dialog.value = false
  items.value = []
  templates.value = []
  rules.value = []
  void load()
}, { immediate: true })
</script>

<template>
  <div>
    <div class="filters">
      <el-select v-model="tab" aria-label="检验设置">
        <el-option v-for="(name, value) in kindNames" :key="value" :value="value" :label="name" />
      </el-select>
      <el-button v-if="manage" type="primary" @click="open()">新建{{ kindNames[tab] }}</el-button>
    </div>
    <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false" />
    <el-table v-if="tab === 'items'" v-loading="loading" :data="items" empty-text="暂无检验项目">
      <el-table-column prop="code" label="编码" />
      <el-table-column prop="name" label="名称" />
      <el-table-column label="类型"><template #default="{ row }">{{ typeLabels[row.type as QualityItem['type']] }}</template></el-table-column>
      <el-table-column prop="unit" label="单位" />
      <el-table-column prop="method" label="检验方法" />
      <el-table-column label="状态"><template #default="{ row }">{{ row.status === 'active' ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column v-if="manage" label="操作"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <el-table v-if="tab === 'templates'" v-loading="loading" :data="templates" empty-text="暂无检验模板">
      <el-table-column prop="code" label="编码" />
      <el-table-column prop="name" label="名称" />
      <el-table-column label="抽样"><template #default="{ row }">{{ samplingLabels[row.sampling as QualityTemplate['sampling']] }}</template></el-table-column>
      <el-table-column label="项目数"><template #default="{ row }">{{ row.checks.length }}</template></el-table-column>
      <el-table-column label="状态"><template #default="{ row }">{{ row.status === 'active' ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column v-if="manage" label="操作"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <el-table v-if="tab === 'sku-rules'" v-loading="loading" :data="rules" empty-text="暂无检验规则">
      <el-table-column label="对象类型"><template #default="{ row }">{{ row.targetType === 'sku' ? '商品' : '分类' }}</template></el-table-column>
      <el-table-column prop="targetId" label="对象" />
      <el-table-column label="供应商"><template #default="{ row }">{{ row.supplierId === '0' ? '全部供应商' : row.supplierId }}</template></el-table-column>
      <el-table-column label="是否检验"><template #default="{ row }">{{ row.inspectionRequired ? '需要检验' : '免检' }}</template></el-table-column>
      <el-table-column label="状态"><template #default="{ row }">{{ row.status === 'active' ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column v-if="manage" label="操作"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <div v-if="current.length >= 200" class="table-footer"><el-button :loading="loading" @click="load(true)">加载更早的记录</el-button></div>
    <el-dialog v-model="dialog" :title="(edit ? '编辑' : '新建') + kindNames[tab]" width="min(900px, 94vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy">
        <div class="sales-form-grid">
          <template v-if="tab !== 'sku-rules'">
            <el-form-item label="编码"><el-input v-model="form.code" maxlength="32" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="form.name" maxlength="80" /></el-form-item>
          </template>
          <el-form-item label="状态"><el-select v-model="form.status"><el-option label="启用" value="active" /><el-option label="停用" value="disabled" /></el-select></el-form-item>
          <template v-if="tab === 'items'">
            <el-form-item label="检验类型"><el-select v-model="form.type"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
            <el-form-item label="单位"><el-input v-model="form.unit" maxlength="24" /></el-form-item>
            <el-form-item label="检验方法"><el-input v-model="form.method" maxlength="200" /></el-form-item>
          </template>
          <template v-else-if="tab === 'templates'">
            <el-form-item label="抽样方式"><el-select v-model="form.sampling"><el-option v-for="(label, value) in samplingLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
            <el-form-item v-if="form.sampling !== 'full'" :label="form.sampling === 'percent' ? '抽样比例 (%)' : '抽样数量'"><el-input v-model="form.samplingValue" /></el-form-item>
          </template>
          <template v-else>
            <el-form-item label="规则对象"><el-select v-model="form.targetType" @change="form.targetId = ''"><el-option label="商品" value="sku" /><el-option label="商品分类" value="category" /></el-select></el-form-item>
            <el-form-item label="商品或分类"><el-select v-model="form.targetId" filterable><el-option v-for="item in targets" :key="item.id" :value="item.id" :label="item.name ?? item.id" /></el-select></el-form-item>
            <el-form-item label="供应商"><el-select v-model="form.supplierId" filterable clearable placeholder="全部供应商"><el-option v-for="item in suppliers" :key="item.id" :value="item.id" :label="item.name ?? item.id" /></el-select></el-form-item>
            <el-form-item label="需要检验"><el-switch v-model="form.inspectionRequired" /></el-form-item>
            <el-form-item v-if="form.inspectionRequired" label="检验模板"><el-select v-model="form.templateId" filterable><el-option v-for="item in templates.filter(value => value.status === 'active')" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
          </template>
        </div>
        <template v-if="tab === 'templates'">
          <div v-for="(check, index) in form.checks" :key="index" class="quality-check">
            <el-form-item :label="'项目 ' + (index + 1)"><el-select v-model="check.itemId" filterable @change="check.lowerLimit = ''; check.upperLimit = ''"><el-option v-for="item in items.filter(value => value.status === 'active')" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
            <el-form-item label="判定标准"><el-input v-model="check.standardValue" maxlength="160" /></el-form-item>
            <template v-if="items.find(item => item.id === check.itemId)?.type === 'quantitative'">
              <el-form-item label="下限"><el-input v-model="check.lowerLimit" /></el-form-item>
              <el-form-item label="上限"><el-input v-model="check.upperLimit" /></el-form-item>
            </template>
            <el-form-item label="必检"><el-switch v-model="check.required" /></el-form-item>
            <el-button link type="danger" @click="form.checks.splice(index, 1)">移除</el-button>
          </div>
          <el-button :disabled="form.checks.length >= 100" @click="addCheck">添加项目</el-button>
        </template>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="dialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.quality-check {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(94px, 1fr));
  align-items: center;
  gap: 8px;
}
</style>
