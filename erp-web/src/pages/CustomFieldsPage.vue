<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import {
  allCustomFields,
  entityLabels,
  fieldLabels,
  formatField,
  saveCustomField,
} from '../api/platform-rules'
import type { CustomField, FieldEntity, FieldInput, FieldValue } from '../api/platform-rules'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const entity = ref<FieldEntity>('sales_order'),
  rows = ref<CustomField[]>([]),
  selected = ref<CustomField>()
const loading = ref(false),
  dialog = ref(false),
  saving = ref(false),
  error = ref(''),
  formError = ref('')
const form = reactive({
  fieldKey: 'cf_',
  label: '',
  type: 'text',
  choices: '',
  dictionaryCode: '',
  defaultValue: '',
  required: false,
  searchable: false,
  listVisible: false,
  printVisible: false,
  importExport: false,
  sortNo: 0,
  enabled: true,
})
let revision = 0

async function load(): Promise<void> {
  const version = ++revision
  rows.value = []
  error.value = ''
  loading.value = true
  try {
    const result = await allCustomFields(entity.value)
    if (version === revision) rows.value = result
  } catch (cause: unknown) {
    if (!isCancelled(cause) && version === revision) error.value = messageOf(cause)
  } finally {
    if (version === revision) loading.value = false
  }
}
function edit(row?: CustomField): void {
  selected.value = row
  formError.value = ''
  Object.assign(form, {
    fieldKey: row?.fieldKey ?? 'cf_',
    label: row?.label ?? '',
    type: row?.type ?? 'text',
    choices: row?.choices.join('\n') ?? '',
    dictionaryCode: row?.dictionaryCode ?? '',
    defaultValue: Array.isArray(row?.defaultValue)
      ? row.defaultValue.join('|')
      : String(row?.defaultValue ?? ''),
    required: row?.required ?? false,
    searchable: row?.indexSlot != null,
    listVisible: row?.listVisible ?? false,
    printVisible: row?.printVisible ?? false,
    importExport: row?.importExport ?? false,
    sortNo: row?.sortNo ?? 0,
    enabled: row?.enabled ?? true,
  })
  dialog.value = true
}
function defaultValue(): FieldValue {
  if (!form.defaultValue.trim()) return null
  return form.type === 'multi_select'
    ? form.defaultValue
        .split('|')
        .map((item) => item.trim())
        .filter(Boolean)
    : form.defaultValue.trim()
}
async function save(): Promise<void> {
  if (saving.value) return
  saving.value = true
  formError.value = ''
  try {
    const data: FieldInput = {
      ...form,
      choices: form.choices
        .split('\n')
        .map((item) => item.trim())
        .filter(Boolean),
      dictionaryCode: form.dictionaryCode.trim() || null,
      defaultValue: defaultValue(),
      version: selected.value?.version,
    }
    await saveCustomField(entity.value, data)
    dialog.value = false
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) formError.value = messageOf(cause)
  } finally {
    saving.value = false
  }
}
watch(
  [entity, () => session.companyId],
  () => {
    dialog.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>自定义字段</h1>
      <p class="muted">为单据和基础资料增加业务信息。</p>
    </div>
    <el-button type="primary" @click="edit()">新增字段</el-button>
  </div>
  <section class="panel">
    <el-select v-model="entity" aria-label="业务类型" style="width: 240px; margin-bottom: 14px">
      <el-option v-for="(name, value) in entityLabels" :key="value" :value="value" :label="name" />
    </el-select>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无自定义字段">
      <el-table-column prop="label" label="字段" min-width="130" /><el-table-column
        prop="fieldKey"
        label="字段编码"
        min-width="150"
      />
      <el-table-column label="类型" min-width="100"
        ><template #default="{ row }">{{
          fieldLabels[row.type as string]
        }}</template></el-table-column
      >
      <el-table-column label="默认值"
        ><template #default="{ row }">{{
          formatField(row.defaultValue)
        }}</template></el-table-column
      >
      <el-table-column label="筛选"
        ><template #default="{ row }">{{
          row.indexSlot ? '已启用' : '—'
        }}</template></el-table-column
      >
      <el-table-column label="状态"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '启用' : '停用'
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row as CustomField)"
            >编辑</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
  <el-dialog
    v-model="dialog"
    :title="selected ? '编辑字段' : '新增字段'"
    width="min(620px,calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <el-form label-width="100px" @submit.prevent="save">
      <el-form-item label="字段标题" required
        ><el-input v-model="form.label" maxlength="80"
      /></el-form-item>
      <el-form-item label="字段编码" required
        ><el-input
          v-model="form.fieldKey"
          maxlength="35"
          :disabled="!!selected"
          placeholder="cf_project_no"
      /></el-form-item>
      <el-form-item label="字段类型"
        ><el-select v-model="form.type" :disabled="!!selected">
          <el-option
            v-for="(name, kind) in fieldLabels"
            :key="kind"
            :label="name"
            :value="kind"
          /> </el-select
      ></el-form-item>
      <template v-if="form.type === 'select' || form.type === 'multi_select'">
        <el-form-item label="字典编码"
          ><el-input v-model="form.dictionaryCode" placeholder="可选，留空时使用下方选项"
        /></el-form-item>
        <el-form-item v-if="!form.dictionaryCode" label="选项"
          ><el-input v-model="form.choices" type="textarea" placeholder="每行一个选项"
        /></el-form-item>
      </template>
      <el-form-item v-if="form.type !== 'user' && form.type !== 'department'" label="默认值"
        ><el-input
          v-model="form.defaultValue"
          :placeholder="form.type === 'multi_select' ? '多个选项用 | 分隔' : undefined"
      /></el-form-item>
      <el-form-item label="排序"
        ><el-input-number v-model="form.sortNo" :min="0" :max="10000"
      /></el-form-item>
      <el-form-item label="用途">
        <el-checkbox v-model="form.required">必填</el-checkbox
        ><el-checkbox
          v-model="form.searchable"
          :disabled="['textarea', 'multi_select'].includes(form.type)"
          >可筛选</el-checkbox
        >
        <el-checkbox v-model="form.listVisible">列表显示</el-checkbox
        ><el-checkbox v-model="form.printVisible">打印</el-checkbox>
        <el-checkbox v-model="form.importExport">导入导出</el-checkbox
        ><el-checkbox v-model="form.enabled">启用</el-checkbox>
      </el-form-item>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
    </el-form>
    <template #footer
      ><el-button @click="dialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save">保存</el-button></template
    >
  </el-dialog>
</template>
