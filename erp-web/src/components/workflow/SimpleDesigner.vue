<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import type {
  Condition,
  Assignee,
  AssigneeStrategy,
  NodeType,
  WorkflowBusinessType,
  WorkflowMetadata,
  WorkflowNode,
} from '../../types/workflow'
import { fieldLabels, nodeLabels } from '../../types/workflow'
import {
  createNode,
  findNode,
  removeNode,
  selective,
  updateTree,
} from '../../utils/workflow-designer'
import SimpleNodeCard from './SimpleNodeCard.vue'
import ConditionEditor from './ConditionEditor.vue'
const props = defineProps<{
  modelValue: WorkflowNode
  metadata: WorkflowMetadata
  businessType: WorkflowBusinessType
  readonly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowNode] }>()
const selected = ref(''),
  drawer = ref(false)
const diagram = ref(false)
const BpmnCanvas = defineAsyncComponent(() => import('./BpmnCanvas.vue'))
const node = computed(() =>
  selected.value ? findNode(props.modelValue, selected.value) : undefined,
)
const fields = computed(() =>
  Object.fromEntries(
    Object.entries(props.metadata.fields).filter(
      ([key]) =>
        !props.metadata.fieldsByType ||
        props.metadata.fieldsByType[props.businessType]?.includes(key),
    ),
  ),
)
const strategies: { value: AssigneeStrategy; label: string }[] = [
  { value: 'users', label: '指定用户' },
  { value: 'role', label: '指定角色' },
  { value: 'dept_leader', label: '部门负责人' },
  { value: 'multi_level_leader', label: '连续多级上级' },
  { value: 'post', label: '指定岗位' },
  { value: 'starter_select', label: '发起人自选' },
  { value: 'starter', label: '发起人本人' },
  { value: 'field_user', label: '单据中的人员字段' },
  { value: 'starter_dept_members', label: '发起部门全员（抄送）' },
]
function select(id: string): void {
  selected.value = id
  drawer.value = true
}
function update(patch: Partial<WorkflowNode>): void {
  if (!props.readonly && node.value)
    emit(
      'update:modelValue',
      updateTree(props.modelValue, node.value.id, (value) => Object.assign(value, patch)),
    )
}
function assignee(patch: Partial<Assignee>): void {
  if (node.value) update({ assignee: { strategy: 'users', ...node.value.assignee, ...patch } })
}
function strategy(value: AssigneeStrategy): void {
  update({
    assignee: {
      strategy: value,
      emptyHandler: node.value?.type === 'copy' ? 'skip' : 'admin',
      level: 1,
      userIds: [],
      roleCodes: [],
      postIds: [],
    },
    multi: value === 'multi_level_leader' ? 'sequential' : 'and',
  })
}
function insert(id: string, type: NodeType): void {
  const next = newNode(type)
  emit(
    'update:modelValue',
    updateTree(props.modelValue, id, (value) => {
      next.child = value.child
      value.child = next
    }),
  )
  select(next.id)
}
function branch(id: string, index: number, type: NodeType): void {
  const next = newNode(type)
  emit(
    'update:modelValue',
    updateTree(props.modelValue, id, (value) => {
      const item = value.branches?.[index]
      if (item) {
        next.child = item.child
        item.child = next
      }
    }),
  )
  select(next.id)
}
function subprocess(id: string, type: NodeType): void {
  const next = newNode(type)
  emit(
    'update:modelValue',
    updateTree(props.modelValue, id, (value) => {
      value.subprocess = next
    }),
  )
  select(next.id)
}
function newNode(type: NodeType): WorkflowNode {
  return createNode(
    type,
    props.businessType === 'wf_generic_request' ? 'ownerDeptId' : 'totalAmount',
  )
}
function remove(): void {
  if (!node.value || node.value.type === 'start' || props.readonly) return
  if (
    (node.value.subprocess || node.value.branches?.some((item) => item.child)) &&
    !window.confirm('删除该分支节点及其分支内环节？后续环节会保留。')
  )
    return
  emit('update:modelValue', removeNode(props.modelValue, node.value.id))
  drawer.value = false
}
function reorderBranch(index: number, offset: number): void {
  if (!node.value?.branches) return
  const branches = [...node.value.branches],
    other = index + offset
  if (other < 0 || other >= branches.length) return
  const current = branches[index]
  branches[index] = branches[other]
  branches[other] = current
  update({ branches })
}
const accessLabels = { read: '只读', write: '可编辑', hidden: '隐藏' } as const
type AccessMode = keyof typeof accessLabels
function fieldKeys(mode: AccessMode): string[] {
  return Object.entries(node.value?.fieldAccess ?? {})
    .filter(([, value]) => value === mode)
    .map(([key]) => key)
}
function setFieldKeys(mode: AccessMode, keys: string[]): void {
  const access = Object.fromEntries(
    Object.entries(node.value?.fieldAccess ?? {}).filter(([, value]) => value !== mode),
  )
  for (const key of keys)
    if (/^[a-z][a-zA-Z0-9_]{0,39}$/.test(key) && !['constructor', 'prototype'].includes(key))
      access[key] = mode
  update({ fieldAccess: access })
}
function editBranch(
  index: number,
  patch: Partial<NonNullable<WorkflowNode['branches']>[number]>,
): void {
  if (!node.value?.branches) return
  const branches = node.value.branches.map((value, item) => ({
    ...value,
    ...(item === index ? patch : {}),
    ...(patch.default && item !== index ? { default: false } : {}),
  }))
  update({ branches })
}
function addBranch(): void {
  if (!node.value?.branches || node.value.branches.length >= 10) return
  update({
    branches: [
      ...node.value.branches,
      {
        name: '新分支',
        condition: selective(node.value.type)
          ? {
              field: props.businessType === 'wf_generic_request' ? 'ownerDeptId' : 'totalAmount',
              op: '>',
              value: props.businessType === 'wf_generic_request' ? '0' : '50000',
            }
          : undefined,
      },
    ],
  })
}
function toggleTimeout(enabled: boolean): void {
  update({ timeout: enabled ? { hours: 24, action: 'remind', repeat: 12 } : undefined })
}
function timeout(patch: Partial<NonNullable<WorkflowNode['timeout']>>): void {
  update({ timeout: { hours: 24, action: 'remind', repeat: 12, ...node.value?.timeout, ...patch } })
}
</script>
<template>
  <section class="flow-designer" aria-label="流程设计器">
    <el-switch
      v-model="diagram"
      inactive-text="SIMPLE"
      active-text="BPMN"
      aria-label="切换 BPMN 画布"
    />
    <p class="muted">
      点击节点配置；分支内添加审批后统一汇合。人工复核、单据权限和期间规则始终由服务端校验。
    </p>
    <BpmnCanvas v-if="diagram" :tree="modelValue" @select="select" />
    <div class="flow-canvas">
      <SimpleNodeCard
        :node="modelValue"
        :selected="selected"
        :readonly="readonly"
        @select="select"
        @insert="insert"
        @branch="branch"
        @subprocess="subprocess"
      /><span class="flow-end">结束并执行原业务审批</span>
    </div>
  </section>
  <el-drawer
    v-model="drawer"
    :title="node ? nodeLabels[node.type] + '设置' : '环节设置'"
    size="min(620px, 96vw)"
    append-to-body
  >
    <el-form v-if="node" label-position="top" :disabled="readonly">
      <el-form-item label="节点名称"
        ><el-input
          :model-value="node.name"
          maxlength="100"
          @update:model-value="(value: unknown) => update({ name: String(value) })"
      /></el-form-item>
      <template v-if="['approver', 'handler', 'copy'].includes(node.type)">
        <el-form-item label="处理人策略"
          ><el-select :model-value="node.assignee?.strategy" @change="strategy"
            ><el-option
              v-for="item in strategies.filter(
                (item) => node?.type === 'copy' || item.value !== 'starter_dept_members',
              )"
              :key="item.value"
              :value="item.value"
              :label="item.label" /></el-select
        ></el-form-item>
        <el-form-item v-if="node.assignee?.strategy === 'users'" label="指定用户"
          ><el-select
            :model-value="node.assignee.userIds ?? []"
            multiple
            filterable
            @update:model-value="(value: string[]) => assignee({ userIds: value })"
            ><el-option
              v-for="user in metadata.users"
              :key="user.id"
              :value="user.id"
              :label="user.name" /></el-select
        ></el-form-item>
        <el-form-item v-if="node.assignee?.strategy === 'role'" label="指定角色"
          ><el-select
            :model-value="node.assignee.roleCodes ?? []"
            multiple
            filterable
            @update:model-value="(value: string[]) => assignee({ roleCodes: value })"
            ><el-option
              v-for="role in metadata.roles"
              :key="role.id"
              :value="role.code"
              :label="role.name + '（' + role.code + '）'" /></el-select
        ></el-form-item>
        <el-form-item v-if="node.assignee?.strategy === 'post'" label="指定岗位"
          ><el-select
            :model-value="node.assignee.postIds ?? []"
            multiple
            filterable
            @update:model-value="(value: string[]) => assignee({ postIds: value })"
            ><el-option
              v-for="post in metadata.posts"
              :key="post.id"
              :value="post.id"
              :label="post.name" /></el-select
          ><el-checkbox
            :model-value="node.assignee.starterDepartment"
            @update:model-value="
              (value: unknown) => assignee({ starterDepartment: Boolean(value) })
            "
            >限定发起人主部门</el-checkbox
          ></el-form-item
        >
        <el-form-item
          v-if="['dept_leader', 'multi_level_leader'].includes(node.assignee?.strategy ?? '')"
          :label="
            node.assignee?.strategy === 'multi_level_leader'
              ? '从本部门逐级审批至第几级上级'
              : '负责人层级（1为本部门，2为上一级）'
          "
          ><el-input
            type="number"
            min="1"
            max="20"
            :model-value="node.assignee?.level ?? 1"
            @update:model-value="(value: unknown) => assignee({ level: Number(value) })"
        /></el-form-item>
        <el-form-item v-if="node.assignee?.strategy === 'field_user'" label="人员字段"
          ><el-select
            :model-value="node.assignee.field"
            @update:model-value="(value: unknown) => assignee({ field: String(value) })"
            ><el-option
              v-for="field in [
                'createdBy',
                ...(businessType === 'sales_order'
                  ? ['salesUserId']
                  : businessType === 'purchase_order'
                    ? ['buyerUserId']
                    : []),
              ]"
              :key="field"
              :value="field"
              :label="fieldLabels[field]" /></el-select
        ></el-form-item>
        <el-form-item v-if="node.type !== 'copy'" label="多人处理方式"
          ><el-select
            :model-value="node.multi ?? 'and'"
            :disabled="node.assignee?.strategy === 'multi_level_leader'"
            @update:model-value="
              (value: unknown) => update({ multi: value as WorkflowNode['multi'] })
            "
            ><el-option label="会签：全部人员同意" value="and" /><el-option
              label="或签：任一人员同意"
              value="or" /><el-option label="依次审批" value="sequential" /></el-select
        ></el-form-item>
        <el-form-item label="没有有效处理人时"
          ><el-select
            :model-value="node.assignee?.emptyHandler ?? 'admin'"
            @update:model-value="
              (value: unknown) => assignee({ emptyHandler: value as Assignee['emptyHandler'] })
            "
            ><el-option label="转给符合业务权限的流程管理员" value="admin" /><el-option
              label="自动驳回"
              value="reject" /><el-option
              v-if="node.type !== 'approver'"
              label="跳过非复核环节"
              value="skip" /></el-select
        ></el-form-item>
        <p class="muted">
          处理人仍须拥有来源单据权限；配置角色或岗位不会额外授权，经办复核分离仍生效。
        </p>
      </template>
      <template v-if="node.type === 'approver' || node.type === 'handler'">
        <el-checkbox
          v-if="node.type === 'approver' && businessType === 'sales_order'"
          :model-value="node.priceApproval"
          @update:model-value="(value: unknown) => update({ priceApproval: Boolean(value) })"
          >人工最低售价审批（处理人须有价格审批权限）</el-checkbox
        >
        <el-checkbox
          v-if="node.type === 'approver' && businessType === 'sales_order'"
          :model-value="node.creditApproval"
          @update:model-value="(value: unknown) => update({ creditApproval: Boolean(value) })"
          >人工信用审批（处理人须有信用审批权及完整财务范围）</el-checkbox
        >
        <el-form-item label="超时规则"
          ><el-switch
            :model-value="Boolean(node.timeout)"
            @update:model-value="(value: unknown) => toggleTimeout(Boolean(value))"
        /></el-form-item>
        <template v-if="node.timeout"
          ><el-form-item label="截止小时数"
            ><el-input
              type="number"
              min="1"
              max="720"
              :model-value="node.timeout.hours"
              @update:model-value="
                (value: unknown) => timeout({ hours: Number(value) })
              " /></el-form-item
          ><el-form-item label="超时处理"
            ><el-select
              :model-value="node.timeout.action"
              @update:model-value="
                (value: unknown) =>
                  timeout({ action: value as NonNullable<WorkflowNode['timeout']>['action'] })
              "
              ><el-option label="发送提醒" value="remind" /><el-option
                label="自动驳回"
                value="reject" /><el-option label="自动终止" value="terminate" /><el-option
                v-if="node.type === 'handler'"
                label="自动完成办理（不计人工复核）"
                value="approve" /></el-select></el-form-item
          ><el-form-item label="再次提醒间隔（小时）"
            ><el-input
              type="number"
              min="1"
              max="168"
              :model-value="node.timeout.repeat ?? 12"
              @update:model-value="
                (value: unknown) => timeout({ repeat: Number(value) })
              " /></el-form-item
        ></template>
      </template>
      <el-form-item v-if="node.type === 'delay'" label="延迟秒数">
        <el-input-number
          :model-value="node.delaySeconds"
          :min="1"
          :max="604800"
          @change="(value: number | undefined) => update({ delaySeconds: value })"
        />
      </el-form-item>
      <el-form-item v-if="node.type === 'trigger'" label="触发事件">
        <el-select
          :model-value="node.triggerEvent"
          @change="(value: WorkflowNode['triggerEvent']) => update({ triggerEvent: value })"
        >
          <el-option value="checkpoint" label="流程到达检查点" />
          <el-option value="review_required" label="通知需要关注" />
        </el-select>
      </el-form-item>
      <template
        v-if="businessType === 'wf_generic_request' && ['approver', 'handler'].includes(node.type)"
      >
        <p class="muted">填写表单字段编码并回车。未配置的字段对审批人隐藏。</p>
        <el-form-item v-for="(label, mode) in accessLabels" :key="mode" :label="label + '字段'">
          <el-select
            :model-value="fieldKeys(mode)"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="50"
            @change="(values: string[]) => setFieldKeys(mode, values)"
          >
            <el-option v-for="key in fieldKeys(mode)" :key="key" :value="key" :label="key" />
          </el-select>
        </el-form-item>
      </template>
      <template v-if="node.branches"
        ><div v-for="(item, index) in node.branches" :key="index" class="flow-branch-settings">
          <el-form-item :label="'分支' + (index + 1) + '名称'"
            ><el-input
              :model-value="item.name"
              maxlength="100"
              @update:model-value="
                (value: unknown) => editBranch(index, { name: String(value) })
              " /></el-form-item
          ><el-checkbox
            v-if="selective(node.type)"
            :model-value="item.default"
            @update:model-value="(value: unknown) => editBranch(index, { default: Boolean(value) })"
            >其他情况走此分支</el-checkbox
          ><ConditionEditor
            v-if="selective(node.type) && !item.default"
            :model-value="item.condition"
            :fields="fields"
            :disabled="readonly"
            @update:model-value="
              (value: unknown) => editBranch(index, { condition: value as Condition | undefined })
            "
          />
          <template v-if="!readonly && node.type === 'route_branch'">
            <el-button :disabled="index === 0" @click="reorderBranch(index, -1)"
              >提高优先级</el-button
            >
            <el-button
              :disabled="index === (node.branches?.length ?? 0) - 1"
              @click="reorderBranch(index, 1)"
              >降低优先级</el-button
            >
          </template>
          <el-button
            v-if="!readonly && node.branches.length > 2"
            @click="update({ branches: node?.branches?.filter((_, at) => at !== index) })"
            >删除此分支</el-button
          >
        </div>
        <el-button v-if="!readonly" :disabled="node.branches.length >= 10" @click="addBranch"
          >增加分支</el-button
        ></template
      >
    </el-form>
    <template #footer
      ><div class="row-actions">
        <el-button @click="drawer = false">完成配置</el-button
        ><el-button v-if="!readonly && node?.type !== 'start'" type="danger" plain @click="remove"
          >删除环节</el-button
        >
      </div></template
    >
  </el-drawer>
</template>
