<script setup lang="ts">
import { ref } from 'vue'
import type { NodeType, WorkflowNode } from '../../types/workflow'
import { nodeLabels } from '../../types/workflow'
const props = defineProps<{ node: WorkflowNode; selected?: string; readonly?: boolean }>()
const emit = defineEmits<{
  select: [id: string]
  insert: [id: string, type: NodeType]
  branch: [id: string, index: number, type: NodeType]
  subprocess: [id: string, type: NodeType]
}>()
const chosen = ref<NodeType>()
function insert(type: NodeType): void {
  if (type) emit('insert', props.node.id, type)
  chosen.value = undefined
}
const choices: NodeType[] = [
  'approver',
  'handler',
  'copy',
  'condition_branch',
  'parallel_branch',
  'inclusive_branch',
  'route_branch',
  'subprocess',
  'delay',
  'trigger',
]
</script>
<template>
  <div class="flow-chain">
    <button
      class="flow-node"
      :class="{ 'is-selected': selected === node.id }"
      :data-node="node.id"
      @click="emit('select', node.id)"
    >
      <span class="flow-node-type">{{ nodeLabels[node.type] }}</span><strong>{{ node.name }}</strong
      ><span v-if="node.type === 'approver'" class="muted"
        >{{
          node.multi === 'or'
            ? '任一人同意'
            : node.multi === 'sequential' || node.assignee?.strategy === 'multi_level_leader'
              ? '依次处理'
              : '全部人员同意'
        }}{{ node.priceApproval ? ' · 人工价格审批' : '' }}</span
      ><span v-else-if="node.type === 'condition_branch'" class="muted"
        >按顺序判断，保留默认分支</span
      ><span v-else-if="node.type === 'parallel_branch'" class="muted">全部分支完成后汇合</span>
      <span v-else-if="node.type === 'inclusive_branch'" class="muted">全部命中分支完成后汇合</span>
      <span v-else-if="node.type === 'route_branch'" class="muted">执行优先级最高的命中分支</span>
      <span v-else-if="node.type === 'delay'" class="muted">等待 {{ node.delaySeconds }} 秒</span>
    </button>
    <div v-if="node.type === 'subprocess'" class="flow-branch flow-subprocess">
      <span class="flow-branch-label">子流程内部</span>
      <SimpleNodeCard
        v-if="node.subprocess"
        :node="node.subprocess"
        :selected="selected"
        :readonly="readonly"
        @select="(id) => emit('select', id)"
        @insert="(id, type) => emit('insert', id, type)"
        @branch="(id, index, type) => emit('branch', id, index, type)"
        @subprocess="(id, type) => emit('subprocess', id, type)"
      />
      <el-select
        v-else-if="!readonly"
        placeholder="添加内部环节"
        aria-label="添加内部环节"
        @change="(type: NodeType) => emit('subprocess', node.id, type)"
      >
        <el-option v-for="type in choices" :key="type" :label="nodeLabels[type]" :value="type" />
      </el-select>
    </div>
    <div v-if="node.branches" class="flow-branches">
      <div v-for="(branch, index) in node.branches" :key="index" class="flow-branch">
        <button class="flow-branch-label" @click="emit('select', node.id)">
          {{ branch.name }}{{ branch.default ? ' · 默认' : '' }}
        </button>
        <SimpleNodeCard
          v-if="branch.child"
          :node="branch.child"
          :selected="selected"
          :readonly="readonly"
          @select="(id) => emit('select', id)"
          @insert="(id, type) => emit('insert', id, type)"
          @branch="(id, branchIndex, type) => emit('branch', id, branchIndex, type)"
          @subprocess="(id, type) => emit('subprocess', id, type)"
        />
        <el-select
          v-else-if="!readonly"
          placeholder="增加分支环节"
          aria-label="增加分支环节"
          @change="(type: NodeType) => emit('branch', node.id, index, type)"
          ><el-option
            v-for="type in choices"
            :key="type"
            :label="nodeLabels[type]"
            :value="type" /></el-select
        ><span v-else class="muted">直接汇合</span>
      </div>
    </div>
    <div v-if="!readonly" class="flow-insert">
      <el-select
        v-model="chosen"
        placeholder="＋ 添加下一个环节"
        :aria-label="node.name + '后添加环节'"
        @change="insert"
        ><el-option v-for="type in choices" :key="type" :label="nodeLabels[type]" :value="type"
      /></el-select>
    </div>
    <SimpleNodeCard
      v-if="node.child"
      :node="node.child"
      :selected="selected"
      :readonly="readonly"
      @select="(id) => emit('select', id)"
      @insert="(id, type) => emit('insert', id, type)"
      @branch="(id, index, type) => emit('branch', id, index, type)"
      @subprocess="(id, type) => emit('subprocess', id, type)"
    />
  </div>
</template>
