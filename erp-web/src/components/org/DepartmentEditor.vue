<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElTreeSelect } from 'element-plus/es/components/tree-select/index.mjs'
import { companyUsers, saveDepartment } from '../../api/org'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { departmentTree } from '../../types/org'
import type { Department, OrgUser } from '../../types/org'
import RecordFrame from '../RecordFrame.vue'

const props = defineProps<{ row?: Department; departments: Department[]; parentId?: string }>()
const emit = defineEmits<{
  saved: [value: Department]
  close: []
  dirty: [value: boolean]
  pending: [value: boolean]
}>()
const form = reactive({
  code: props.row?.code ?? '',
  name: props.row?.name ?? '',
  parentId: props.row?.parentId ?? props.parentId ?? '0',
  leaderUserId: props.row?.leaderUserId ?? '',
  sortNo: props.row?.sortNo ?? 0,
})
const busy = ref(false),
  error = ref(''),
  uncertain = ref(false),
  conflict = ref(false),
  leaders = ref<OrgUser[]>([])
const parentTree = computed(() => [
  {
    id: '0',
    name: '顶级部门',
    children: departmentTree(props.departments.filter((row) => row.status === 'active')),
  },
])
const baseline = JSON.stringify(form),
  key = newIdempotencyKey()
let sequence = 0,
  controller = new AbortController()
watch(form, () => emit('dirty', JSON.stringify(form) !== baseline), { deep: true, flush: 'sync' })
watch(busy, (value) => emit('pending', value), { flush: 'sync' })
async function searchLeaders(keyword: string): Promise<void> {
  const current = ++sequence
  controller.abort()
  controller = new AbortController()
  try {
    const result = await companyUsers(keyword, undefined, controller.signal)
    if (current === sequence) {
      leaders.value = result.items.filter((user) => user.status === 'active')
      if (
        props.row?.leaderUserId &&
        !leaders.value.some((user) => user.id === props.row?.leaderUserId)
      )
        leaders.value.push({
          id: props.row.leaderUserId,
          username: '',
          displayName: props.row.leaderName ?? '原负责人',
          status: 'active',
        })
    }
  } catch (cause: unknown) {
    if (current === sequence && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function save(): Promise<void> {
  if (busy.value || conflict.value) return
  if (!form.code.trim() || !form.name.trim()) {
    error.value = '请填写部门编码和名称'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await saveDepartment(
      {
        ...form,
        code: form.code.trim(),
        name: form.name.trim(),
        leaderUserId: form.leaderUserId || null,
        version: props.row?.version,
      },
      key,
      props.row?.id,
    )
    emit('dirty', false)
    emit('saved', result)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      uncertain.value =
        cause instanceof ApiError && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
      conflict.value = cause instanceof ApiError && cause.code === 'VERSION_CONFLICT'
    }
  } finally {
    busy.value = false
  }
}
onMounted(() => {
  void searchLeaders('')
})
onScopeDispose(() => {
  ++sequence
  controller.abort()
})
</script>

<template>
  <RecordFrame inline :busy="busy" :title="row ? '编辑部门' : '新建部门'" @close="emit('close')">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="save">
      <fieldset :disabled="busy || uncertain || conflict" class="form-fieldset">
        <el-form-item label="部门编码" for="department-code" required
          ><el-input id="department-code" v-model="form.code" maxlength="32"
        /></el-form-item>
        <el-form-item label="部门名称" for="department-name" required
          ><el-input id="department-name" v-model="form.name" maxlength="100"
        /></el-form-item>
        <el-form-item label="上级部门" for="department-parent"
          ><el-tree-select
            id="department-parent"
            v-model="form.parentId"
            :data="parentTree"
            :props="{ label: 'name' }"
            node-key="id"
            check-strictly
            :disabled="Boolean(row) || busy || uncertain || conflict"
        /></el-form-item>
        <p v-if="row" class="muted">调整上级请在部门树中拖拽，并确认移动。</p>
        <el-form-item label="部门负责人" for="department-leader"
          ><el-select
            id="department-leader"
            v-model="form.leaderUserId"
            clearable
            filterable
            remote
            :remote-method="searchLeaders"
            :disabled="busy || uncertain || conflict"
            ><el-option
              v-for="user in leaders"
              :key="user.id"
              :value="user.id"
              :label="
                user.displayName + (user.username ? '（' + user.username + '）' : '')
              " /></el-select
        ></el-form-item>
        <el-form-item label="部门排序" for="department-sort"
          ><el-input-number
            id="department-sort"
            v-model="form.sortNo"
            :min="0"
            :max="1000000"
            :precision="0"
            :disabled="busy || uncertain || conflict"
        /></el-form-item>
      </fieldset>
      <p v-if="uncertain">保存结果尚未确认，可使用相同内容重试，或关闭后刷新核对。</p>
    </el-form>
    <template #footer
      ><el-button :disabled="busy" @click="emit('close')">取消</el-button
      ><el-button type="primary" :disabled="conflict" :loading="busy" @click="save">{{
        uncertain ? '重试原保存' : '保存部门'
      }}</el-button></template
    >
  </RecordFrame>
</template>

<style scoped>
.el-select,
.el-tree-select {
  width: 100%;
}
</style>
