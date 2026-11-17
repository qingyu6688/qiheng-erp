<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import {
  saveWorkflowForm,
  startWorkflowRequest,
  workflowFormModels,
  workflowForms,
} from '../../api/workflow'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import type {
  Choice,
  WorkflowForm,
  WorkflowFormField,
  WorkflowFormValues,
} from '../../types/workflow'
import { useSessionStore } from '../../stores/session'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import DocumentFilesPanel from '../../components/DocumentFilesPanel.vue'

const route = useRoute(),
  session = useSessionStore()
const WorkflowFormFields = defineAsyncComponent(
  () => import('../../components/workflow/WorkflowFormFields.vue'),
)
const manage = computed(() => route.name === 'workflow-forms')
const forms = ref<WorkflowForm[]>([]),
  models = ref<Choice[]>([])
const activeForm = ref<string>(),
  title = ref(''),
  values = ref<WorkflowFormValues>({})
const selected = computed(() => forms.value.find((item) => item.id === activeForm.value))
const result = ref<Awaited<ReturnType<typeof startWorkflowRequest>>>()
const editor = ref<Omit<WorkflowForm, 'modelName'>>()
const preview = ref(false),
  previewValues = ref<WorkflowFormValues>({})
const loading = ref(false),
  busy = ref(false),
  uncertain = ref(false),
  error = ref('')
let revision = 0,
  writeKey = '',
  editingSnapshot = ''
const locked = computed(() => busy.value || uncertain.value)
const fieldKinds: Record<WorkflowFormField['type'], string> = {
  text: '文本',
  number: '数字',
  date: '日期',
  select: '单选',
  boolean: '是 / 否',
}

async function load(): Promise<void> {
  const token = ++revision
  loading.value = true
  error.value = ''
  try {
    const [rows, published] = await Promise.all([
      workflowForms(manage.value),
      manage.value ? workflowFormModels() : [],
    ])
    if (token !== revision) return
    forms.value = rows
    models.value = published
  } catch (cause: unknown) {
    if (token === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (token === revision) loading.value = false
  }
}

function edit(form?: WorkflowForm): void {
  if (locked.value) return
  editor.value = form
    ? {
        id: form.id,
        code: form.code,
        name: form.name,
        modelId: form.modelId,
        enabled: Boolean(form.enabled),
        version: form.version,
        fields: form.fields.map((field) => ({
          ...field,
          options: field.options ? [...field.options] : [],
        })),
      }
    : {
        id: '',
        code: '',
        name: '',
        modelId: '',
        enabled: false,
        version: 0,
        fields: [{ key: '', label: '', type: 'text', required: false }],
      }
  editingSnapshot = JSON.stringify(editor.value)
  preview.value = false
  previewValues.value = {}
  error.value = ''
}
function moveField(index: number, offset: number): void {
  const fields = editor.value?.fields
  const destination = index + offset
  if (!fields || destination < 0 || destination >= fields.length) return
  const field = fields[index]
  fields[index] = fields[destination]
  fields[destination] = field
}

function changeForm(id: string): void {
  activeForm.value = id
  values.value = {}
  result.value = undefined
  writeKey = ''
}

function failure(cause: unknown): void {
  uncertain.value =
    !isCancelled(cause) &&
    (!(cause instanceof ApiError) ||
      cause.status === 0 ||
      cause.status >= 500 ||
      cause.code === 'REQUEST_IN_PROGRESS')
  if (!uncertain.value) writeKey = ''
  if (!isCancelled(cause))
    error.value = uncertain.value ? '提交结果暂不明确，请保留当前内容重试' : messageOf(cause)
}

async function save(): Promise<void> {
  if (!editor.value || busy.value) return
  const token = revision
  busy.value = true
  error.value = ''
  writeKey ||= newIdempotencyKey()
  try {
    await saveWorkflowForm(editor.value, writeKey)
    if (token !== revision) return
    editor.value = undefined
    uncertain.value = false
    writeKey = ''
    await load()
  } catch (cause: unknown) {
    if (token === revision) failure(cause)
  } finally {
    busy.value = false
  }
}

async function submit(): Promise<void> {
  if (!selected.value || !title.value.trim() || busy.value) return
  const token = revision
  busy.value = true
  error.value = ''
  writeKey ||= newIdempotencyKey()
  try {
    result.value = await startWorkflowRequest(
      selected.value.id,
      selected.value.version,
      title.value,
      values.value,
      writeKey,
    )
    if (token !== revision) {
      result.value = undefined
      return
    }
    title.value = ''
    values.value = {}
    uncertain.value = false
    writeKey = ''
  } catch (cause: unknown) {
    if (token === revision) failure(cause)
  } finally {
    if (token === revision) busy.value = false
  }
}

function mayLeave(): boolean {
  if (locked.value) return false
  const hasDraft = editor.value
    ? JSON.stringify(editor.value) !== editingSnapshot
    : Boolean(
        title.value.trim() ||
        Object.values(values.value).some((value) => value != null && value !== ''),
      )
  return !hasDraft || window.confirm('申请或表单有未保存的内容，确定离开？')
}
const unregister = registerCompanyLeaveGuard(mayLeave)
onBeforeRouteLeave(mayLeave)
watch(
  () => [session.companyId, manage.value],
  () => {
    forms.value = []
    models.value = []
    editor.value = undefined
    activeForm.value = undefined
    result.value = undefined
    title.value = ''
    values.value = {}
    busy.value = false
    uncertain.value = false
    writeKey = ''
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  unregister()
})
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>{{ manage ? '通用审批表单' : '发起通用申请' }}</h1>
      <p>
        {{
          manage ? '为已发布的通用审批流程设计申请表单。' : '选择流程对应的申请表单并填写业务内容。'
        }}
      </p>
    </div>
    <div class="row-actions">
      <el-button :loading="loading" :disabled="locked" @click="load">刷新</el-button>
      <el-button v-if="manage" type="primary" :disabled="locked" @click="edit()"
        >新建表单</el-button
      >
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section v-if="manage" class="panel workflow-form-page">
    <el-table :data="forms" v-loading="loading" row-key="id">
      <el-table-column prop="code" label="编码" />
      <el-table-column prop="name" label="表单名称" />
      <el-table-column prop="modelName" label="审批流程" />
      <el-table-column label="状态" width="120"
        ><template #default="{ row }">{{
          row.enabled ? '启用' : '停用'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="110"
        ><template #default="{ row }">
          <el-button text type="primary" :disabled="locked" @click="edit(row)">编辑</el-button>
        </template></el-table-column
      >
    </el-table>
  </section>
  <section v-else class="panel workflow-form-page" v-loading="loading">
    <el-form label-position="top" :disabled="locked">
      <el-form-item label="申请表单">
        <el-select :model-value="activeForm" placeholder="选择表单" @change="changeForm">
          <el-option v-for="item in forms" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <p v-if="!forms.length && !loading" class="muted">当前公司没有可发起的表单</p>
      <el-form-item v-if="selected" label="申请标题"
        ><el-input v-model="title" maxlength="100"
      /></el-form-item>
    </el-form>
    <WorkflowFormFields
      v-if="selected"
      :key="selected.id"
      v-model="values"
      :fields="selected.fields"
      :disabled="locked"
    />
    <el-button
      v-if="selected"
      type="primary"
      :disabled="!title.trim() && !uncertain"
      :loading="busy"
      @click="submit"
      >{{ uncertain ? '重试原申请' : '提交申请' }}</el-button
    >
  </section>
  <section v-if="result" class="panel workflow-form-page">
    <h2>申请已提交</h2>
    <p>申请编号 {{ result.requestNo }}</p>
    <router-link class="text-link" :to="'/workflow/instances/' + result.instanceId"
      >查看审批进度</router-link
    >
    <DocumentFilesPanel owner-type="wf_generic_request" :owner-id="result.id" />
  </section>
  <el-drawer
    :model-value="Boolean(editor)"
    title="设计审批表单"
    size="min(760px, 96vw)"
    :close-on-press-escape="!locked"
    :close-on-click-modal="!locked"
    :show-close="!locked"
    @close="!locked && (editor = undefined)"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="editor" label-position="top" :disabled="locked">
      <el-form-item label="表单编码"
        ><el-input v-model="editor.code" :disabled="Boolean(editor.id)" maxlength="40"
      /></el-form-item>
      <el-form-item label="表单名称"
        ><el-input v-model="editor.name" maxlength="100"
      /></el-form-item>
      <el-form-item label="已发布的流程"
        ><el-select v-model="editor.modelId" filterable>
          <el-option
            v-for="item in models"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          /> </el-select
      ></el-form-item>
      <el-form-item label="启用表单"><el-switch v-model="editor.enabled" /></el-form-item>
      <h3>表单字段</h3>
      <div v-for="(field, index) in editor.fields" :key="index" class="workflow-field-row">
        <el-form-item label="字段编码"
          ><el-input v-model="field.key" maxlength="40"
        /></el-form-item>
        <el-form-item label="显示名称"
          ><el-input v-model="field.label" maxlength="100"
        /></el-form-item>
        <el-form-item label="控件"
          ><el-select v-model="field.type" @change="field.options = []">
            <el-option
              v-for="(label, kind) in fieldKinds"
              :key="kind"
              :label="label"
              :value="kind"
            /> </el-select
        ></el-form-item>
        <el-form-item label="必填"
          ><el-checkbox v-model="field.required" aria-label="必填"
        /></el-form-item>
        <el-button
          text
          type="danger"
          :disabled="editor.fields.length < 2"
          @click="editor.fields.splice(index, 1)"
          >移除</el-button
        >
        <div class="workflow-field-options">
          <el-button size="small" :disabled="index === 0" @click="moveField(index, -1)"
            >上移</el-button
          >
          <el-button
            size="small"
            :disabled="index === (editor?.fields.length ?? 0) - 1"
            @click="moveField(index, 1)"
            >下移</el-button
          >
        </div>
        <el-form-item v-if="field.type === 'select'" label="选项" class="workflow-field-options">
          <el-select
            v-model="field.options"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="50"
            placeholder="填写选项后回车"
          />
        </el-form-item>
      </div>
      <el-button
        :disabled="editor.fields.length >= 50"
        @click="editor.fields.push({ key: '', label: '', type: 'text', required: false })"
        >添加字段</el-button
      >
      <el-button @click="preview = !preview">{{ preview ? '收起预览' : '预览表单' }}</el-button>
    </el-form>
    <section v-if="preview && editor" class="workflow-form-preview">
      <h3>表单预览</h3>
      <WorkflowFormFields
        v-model="previewValues"
        :fields="editor.fields.filter((field) => field.key && field.label)"
        :disabled="locked"
      />
    </section>
    <template #footer
      ><el-button type="primary" :loading="busy" @click="save">{{
        uncertain ? '重试原配置' : '保存表单'
      }}</el-button></template
    >
  </el-drawer>
</template>

<style scoped>
.workflow-form-page {
  margin-top: 16px;
  padding: 18px;
}
.workflow-field-row {
  display: grid;
  grid-template-columns: 1fr 1fr 104px 48px 52px;
  gap: 10px;
  align-items: baseline;
  padding: 10px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}
.workflow-field-options {
  grid-column: 1 / -1;
}
.workflow-form-preview {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
}
@media (max-width: 600px) {
  .workflow-field-row {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
