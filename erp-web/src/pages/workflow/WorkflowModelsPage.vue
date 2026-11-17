<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  workflowModelVersion,
  workflowBindings,
  workflowCategories,
  workflowMetadata,
  workflowModel,
  workflowModels,
  saveWorkflowBinding,
  saveWorkflowCategory,
  saveWorkflowModel,
  workflowModelAction,
} from '../../api/workflow'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type {
  WorkflowNode,
  BindingInput,
  ModelInput,
  WorkflowBinding,
  WorkflowBusinessType,
  WorkflowMetadata,
  WorkflowModel,
} from '../../types/workflow'
import { businessLabels, workflowStatus } from '../../types/workflow'
import { createNode, modelProblems } from '../../utils/workflow-designer'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import SimpleDesigner from '../../components/workflow/SimpleDesigner.vue'
import ConditionEditor from '../../components/workflow/ConditionEditor.vue'
import { formatDateTime } from '../../utils/format'
const session = useSessionStore(),
  rows = ref<WorkflowModel[]>([]),
  bindings = ref<WorkflowBinding[]>([]),
  metadata = ref<WorkflowMetadata>(),
  categories = ref<Awaited<ReturnType<typeof workflowCategories>>>([])
const error = ref(''),
  loading = ref(false),
  busy = ref(false),
  uncertain = ref(false),
  editor = ref(false),
  bindingEditor = ref(false),
  categoryEditor = ref(false),
  selected = ref<string>(),
  bindingId = ref<string>(),
  versions = ref<WorkflowModel>()
const draft = ref<ModelInput>(),
  binding = ref<BindingInput>(),
  category = ref({ code: '', name: '', sort: 0, version: 0 })
let baseline = '',
  bindingBaseline = '',
  revision = 0,
  saveKey = newIdempotencyKey(),
  bindingKey = newIdempotencyKey(),
  categoryKey = newIdempotencyKey(),
  frozenModel: ModelInput | undefined,
  frozenBinding: BindingInput | undefined
const history = ref<{ version: number; simple: WorkflowNode }>()
let frozenCategory: typeof category.value | undefined
async function showVersion(version: number): Promise<void> {
  if (!selected.value || busy.value) return
  const current = revision
  try {
    const result = await workflowModelVersion(selected.value, version)
    if (current === revision) history.value = { version, simple: result.simple }
  } catch (cause: unknown) {
    if (current === revision) error.value = messageOf(cause)
  }
}
const bindingFields = computed(() =>
  Object.fromEntries(
    Object.entries(metadata.value?.fields ?? {}).filter(
      ([key]) =>
        !binding.value ||
        !metadata.value?.fieldsByType ||
        metadata.value.fieldsByType[binding.value.businessType]?.includes(key),
    ),
  ),
)
const bindingLabels = Object.fromEntries(
  Object.entries(businessLabels).filter(([type]) => type !== 'wf_generic_request'),
)
const actionKeys = new Map<string, { key: string; version: number }>()
const dirty = computed(
  () =>
    (editor.value && JSON.stringify(draft.value) !== baseline) ||
    (bindingEditor.value && JSON.stringify(binding.value) !== bindingBaseline) ||
    (categoryEditor.value && Boolean(category.value.code || category.value.name)),
)
function mayLeave(): boolean {
  if (busy.value || uncertain.value) return false
  return !dirty.value || window.confirm('有未保存的流程配置，确定离开？')
}
const unregister = registerCompanyLeaveGuard(mayLeave)
onBeforeRouteLeave(mayLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (dirty.value || uncertain.value || busy.value) event.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
function unknown(cause: unknown): boolean {
  return (
    !(cause instanceof ApiError) ||
    cause.status === 0 ||
    cause.status >= 500 ||
    cause.code === 'REQUEST_IN_PROGRESS'
  )
}
async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const [models, options, groups, links] = await Promise.all([
      workflowModels(),
      workflowMetadata(),
      workflowCategories(),
      workflowBindings(),
    ])
    if (current !== revision) return
    rows.value = models
    metadata.value = options
    categories.value = groups
    bindings.value = links
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      rows.value = []
      bindings.value = []
      metadata.value = undefined
    }
  } finally {
    if (current === revision) loading.value = false
  }
}
async function edit(row?: WorkflowModel): Promise<void> {
  if (!mayLeave()) return
  error.value = ''
  uncertain.value = false
  frozenModel = undefined
  saveKey = newIdempotencyKey()
  selected.value = row?.id
  try {
    const value = row ? await workflowModel(row.id) : undefined
    draft.value = value?.simple
      ? {
          companyId: value.companyId,
          code: value.code,
          name: value.name,
          categoryId: value.categoryId,
          businessType: value.businessType,
          simple: value.simple,
          version: value.version,
        }
      : {
          companyId: session.companyId!,
          code: '',
          name: '',
          businessType: 'sales_order',
          simple: { id: 'root', type: 'start', name: '发起人', child: createNode('approver') },
          version: 0,
        }
    baseline = JSON.stringify(draft.value)
    editor.value = true
    versions.value = value
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
function close(): void {
  if (mayLeave()) {
    editor.value = false
    bindingEditor.value = false
    categoryEditor.value = false
    draft.value = undefined
    binding.value = undefined
    baseline = ''
    bindingBaseline = ''
  }
}
async function save(): Promise<void> {
  if (!draft.value || busy.value) return
  error.value = ''
  if (!frozenModel) {
    const issues = modelProblems(draft.value.simple)
    if (issues.length) {
      error.value = issues.join('；')
      return
    }
    frozenModel = clone(draft.value)
  }
  busy.value = true
  try {
    await saveWorkflowModel(selected.value, frozenModel, saveKey)
    uncertain.value = false
    frozenModel = undefined
    editor.value = false
    baseline = ''
    await load()
  } catch (cause: unknown) {
    uncertain.value = unknown(cause)
    error.value = messageOf(cause)
    if (!uncertain.value) {
      frozenModel = undefined
      saveKey = newIdempotencyKey()
    }
  } finally {
    busy.value = false
  }
}
async function action(row: WorkflowModel, name: 'publish' | 'disable'): Promise<void> {
  if (busy.value || !mayLeave()) return
  const id = row.id + ':' + name,
    pending = actionKeys.get(id) ?? { key: newIdempotencyKey(), version: row.version }
  actionKeys.set(id, pending)
  busy.value = true
  error.value = ''
  try {
    await workflowModelAction(row.id, name, pending.version, pending.key)
    actionKeys.delete(id)
    await load()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    if (!unknown(cause)) actionKeys.delete(id)
  } finally {
    busy.value = false
  }
}
function editBinding(row?: WorkflowBinding): void {
  if (!mayLeave()) return
  error.value = ''
  uncertain.value = false
  frozenBinding = undefined
  bindingKey = newIdempotencyKey()
  bindingId.value = row?.id
  let condition = row?.conditionJson
  if (typeof condition === 'string') {
    try {
      condition = JSON.parse(condition) as BindingInput['condition']
    } catch {
      error.value = '绑定条件无法读取，请联系管理员'
      return
    }
  }
  binding.value = row
    ? {
        companyId: row.companyId,
        businessType: row.businessType,
        modelId: row.modelId,
        priority: row.priority,
        condition,
        enabled: Boolean(row.enabled),
        version: row.version,
      }
    : {
        companyId: session.companyId!,
        businessType: 'sales_order',
        modelId: '',
        priority: 0,
        enabled: true,
        version: 0,
      }
  bindingBaseline = JSON.stringify(binding.value)
  bindingEditor.value = true
}
async function saveBinding(): Promise<void> {
  if (!binding.value || busy.value) return
  busy.value = true
  error.value = ''
  frozenBinding ??= clone(binding.value)
  try {
    await saveWorkflowBinding(bindingId.value, frozenBinding, bindingKey)
    uncertain.value = false
    frozenBinding = undefined
    bindingEditor.value = false
    bindingBaseline = ''
    await load()
  } catch (cause: unknown) {
    uncertain.value = unknown(cause)
    error.value = messageOf(cause)
    if (!uncertain.value) {
      frozenBinding = undefined
      bindingKey = newIdempotencyKey()
    }
  } finally {
    busy.value = false
  }
}
function newCategory(): void {
  category.value = { code: '', name: '', sort: 0, version: 0 }
  categoryKey = newIdempotencyKey()
  frozenCategory = undefined
  uncertain.value = false
  categoryEditor.value = true
}
async function createCategory(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await saveWorkflowCategory(undefined, (frozenCategory ??= clone(category.value)), categoryKey)
    uncertain.value = false
    frozenCategory = undefined
    categoryEditor.value = false
    category.value = { code: '', name: '', sort: 0, version: 0 }
    await load()
  } catch (cause: unknown) {
    uncertain.value = unknown(cause)
    error.value = messageOf(cause)
    if (!uncertain.value) {
      frozenCategory = undefined
      categoryKey = newIdempotencyKey()
    }
  } finally {
    busy.value = false
  }
}
watch(
  () => session.companyId,
  () => {
    ++revision
    editor.value = false
    bindingEditor.value = false
    categoryEditor.value = false
    draft.value = undefined
    rows.value = []
    bindings.value = []
    uncertain.value = false
    actionKeys.clear()
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  unregister()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>流程设计与发布</h1>
      <p>运行实例使用已发布版本；保存草稿不会改变正在审批的单据。</p>
    </div>
    <div class="row-actions">
      <el-button v-if="session.can('workflow_category:update')" @click="newCategory"
        >新增分类</el-button
      ><el-button
        v-if="session.can('workflow_model:create')"
        type="primary"
        :disabled="loading || busy"
        @click="edit()"
        >新建流程</el-button
      >
    </div>
  </div>
  <el-alert
    v-if="error && !editor && !bindingEditor && !categoryEditor"
    :title="error"
    type="error"
    :closable="false"
  />
  <section class="panel table-panel" :aria-busy="loading">
    <el-table :data="rows" row-key="id"
      ><el-table-column
        prop="name"
        label="流程名称"
        min-width="200"
        show-overflow-tooltip
      /><el-table-column label="业务类型" min-width="130"
        ><template #default="{ row }">{{
          businessLabels[row.businessType as WorkflowBusinessType]
        }}</template></el-table-column
      ><el-table-column label="公司范围" min-width="130"
        ><template #default="{ row }">{{
          row.companyId === '0' ? '企业共享' : '当前公司'
        }}</template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }">{{ workflowStatus[row.status] }}</template></el-table-column
      ><el-table-column prop="publishedVersion" label="发布版本" width="100" /><el-table-column
        label="操作"
        min-width="280"
        fixed="right"
        ><template #default="{ row }"
          ><div class="row-actions">
            <button class="text-link" @click="edit(row)">
              {{ session.can('workflow_model:update') ? '设计与历史' : '查看' }}</button><button
              v-if="session.can('workflow_model:publish')"
              class="text-link"
              :disabled="busy"
              @click="action(row, 'publish')"
            >
              发布新版本</button
            ><button
              v-if="row.status === 'published' && session.can('workflow_model:disable')"
              class="text-link"
              :disabled="busy"
              @click="action(row, 'disable')"
            >
              停用
            </button>
          </div></template
        ></el-table-column
      ></el-table
    >
  </section>
  <div class="page-heading">
    <div>
      <h2>单据绑定</h2>
      <p>优先使用公司绑定，再按优先级从高到低匹配；未匹配的单据保持原单级审批。</p>
    </div>
    <el-button v-if="session.can('workflow_binding:update')" @click="editBinding()"
      >新增绑定</el-button
    >
  </div>
  <section class="panel table-panel">
    <el-table :data="bindings" row-key="id"
      ><el-table-column label="业务类型" min-width="130"
        ><template #default="{ row }">{{
          businessLabels[row.businessType as WorkflowBusinessType]
        }}</template></el-table-column
      ><el-table-column prop="modelName" label="流程" min-width="180" /><el-table-column
        label="公司范围"
        min-width="120"
        ><template #default="{ row }">{{
          row.companyId === '0' ? '企业共享' : '当前公司'
        }}</template></el-table-column
      ><el-table-column prop="priority" label="优先级" width="100" /><el-table-column
        label="状态"
        width="100"
        ><template #default="{ row }">{{
          row.enabled ? '启用' : '停用'
        }}</template></el-table-column
      ><el-table-column label="操作" width="110"
        ><template #default="{ row }"
          ><button
            v-if="session.can('workflow_binding:update')"
            class="text-link"
            @click="editBinding(row)"
          >
            维护绑定
          </button></template
        ></el-table-column
      ></el-table
    >
  </section>
  <el-dialog
    v-model="editor"
    :title="selected ? '流程设计与发布历史' : '新建流程'"
    width="min(1320px, 96vw)"
    :before-close="close"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <template v-if="draft && metadata"
      ><el-form
        label-position="top"
        class="flow-model-fields"
        :disabled="busy || uncertain || Boolean(selected && !session.can('workflow_model:update'))"
        ><el-form-item label="流程编码"
          ><el-input
            v-model="draft.code"
            :disabled="Boolean(selected)"
            maxlength="40" /></el-form-item
        ><el-form-item label="流程名称"
          ><el-input v-model="draft.name" maxlength="100" /></el-form-item
        ><el-form-item label="业务类型"
          ><el-select v-model="draft.businessType" :disabled="Boolean(selected)"
            ><el-option
              v-for="(label, value) in businessLabels"
              :key="value"
              :value="value"
              :label="label" /></el-select></el-form-item
        ><el-form-item label="公司范围"
          ><el-select v-model="draft.companyId" :disabled="Boolean(selected)"
            ><el-option label="当前公司" :value="session.companyId" /><el-option
              label="企业共享"
              value="0" /></el-select></el-form-item
        ><el-form-item label="流程分类"
          ><el-select v-model="draft.categoryId" clearable
            ><el-option
              v-for="item in categories"
              :key="item.id"
              :value="item.id"
              :label="item.name" /></el-select></el-form-item
      ></el-form>
      <SimpleDesigner
        v-model="draft.simple"
        :metadata="metadata"
        :business-type="draft.businessType"
        :readonly="busy || uncertain || Boolean(selected && !session.can('workflow_model:update'))"
      />
      <section v-if="versions?.versions?.length">
        <h3>发布历史</h3>
        <el-table :data="versions.versions"
          ><el-table-column prop="versionNo" label="发布版本" /><el-table-column label="快照"
            ><template #default="{ row }"
              ><button class="text-link" @click="showVersion(row.versionNo)">
                查看该版本
              </button></template
            ></el-table-column
          ><el-table-column label="发布时间" min-width="180"
            ><template #default="{ row }">{{
              formatDateTime(row.publishedAt)
            }}</template></el-table-column
          ><el-table-column
            prop="publishedBy"
            label="发布人编号"
            min-width="200"
            show-overflow-tooltip
        /></el-table>
      </section> </template
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="uncertain" class="muted">请求结果尚未确认，表单已冻结。请使用原请求重试。</p>
    <template #footer
      ><el-button :disabled="busy || uncertain" @click="close">关闭</el-button
      ><el-button
        v-if="
          selected ? session.can('workflow_model:update') : session.can('workflow_model:create')
        "
        type="primary"
        :loading="busy"
        @click="save"
        >{{ uncertain ? '使用原请求重试' : '保存草稿' }}</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    v-model="bindingEditor"
    title="单据流程绑定"
    width="min(680px, 94vw)"
    :before-close="close"
    :close-on-click-modal="false"
    ><el-form v-if="binding && metadata" label-position="top" :disabled="busy || uncertain"
      ><el-form-item label="业务类型"
        ><el-select v-model="binding.businessType" :disabled="Boolean(bindingId)"
          ><el-option
            v-for="(label, value) in bindingLabels"
            :key="value"
            :value="value"
            :label="label" /></el-select></el-form-item
      ><el-form-item label="公司范围"
        ><el-select v-model="binding.companyId" :disabled="Boolean(bindingId)"
          ><el-option label="当前公司" :value="session.companyId" /><el-option
            label="企业共享"
            value="0" /></el-select></el-form-item
      ><el-form-item label="流程模型"
        ><el-select v-model="binding.modelId" filterable
          ><el-option
            v-for="model in rows.filter(
              (item) =>
                item.businessType === binding?.businessType &&
                (item.status === 'published' || item.id === binding?.modelId) &&
                (item.companyId === '0' || item.companyId === binding?.companyId),
            )"
            :key="model.id"
            :value="model.id"
            :label="model.name" /></el-select></el-form-item
      ><el-form-item label="优先级（同范围不能重复）"
        ><el-input
          v-model.number="binding.priority"
          type="number"
          min="-1000"
          max="1000" /></el-form-item
      ><el-form-item label="匹配条件"
        ><ConditionEditor
          v-model="binding.condition"
          :fields="bindingFields"
          :disabled="busy || uncertain" /></el-form-item
      ><el-form-item label="启用绑定"
        ><el-switch v-model="binding.enabled" /></el-form-item></el-form
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="uncertain" class="muted">请求结果未确认，请保留当前表单并使用原请求重试。</p>
    <template #footer
      ><el-button :disabled="busy || uncertain" @click="close">取消</el-button
      ><el-button type="primary" :loading="busy" @click="saveBinding">{{
        uncertain ? '使用原请求重试' : '保存绑定'
      }}</el-button></template
    ></el-dialog
  >
  <el-dialog
    v-model="categoryEditor"
    title="新增流程分类"
    width="min(520px, 94vw)"
    :before-close="close"
    ><el-form label-position="top" :disabled="busy || uncertain"
      ><el-form-item label="分类编码"
        ><el-input v-model="category.code" maxlength="40" /></el-form-item
      ><el-form-item label="分类名称"
        ><el-input v-model="category.name" maxlength="100" /></el-form-item
      ><el-form-item label="排序"
        ><el-input v-model.number="category.sort" type="number" /></el-form-item></el-form
    ><el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer
      ><el-button :disabled="busy" @click="close">取消</el-button
      ><el-button type="primary" :loading="busy" @click="createCategory"
        >保存分类</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    :model-value="Boolean(history)"
    :title="'发布版本 ' + history?.version"
    width="min(1320px, 96vw)"
    @close="history = undefined"
    ><SimpleDesigner
      v-if="history && metadata && draft"
      :model-value="history.simple"
      :metadata="metadata"
      :business-type="draft.businessType"
      readonly
  /></el-dialog>
</template>
