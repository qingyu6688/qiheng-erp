<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ElTreeSelect } from 'element-plus/es/components/tree-select/index.mjs'
import { roleDataScope, saveRoleDataScope } from '../../api/org'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { departmentTree } from '../../types/org'
import type { DepartmentScopeType, RoleDataScope } from '../../types/org'

const props = defineProps<{ roleId: string }>()
const emit = defineEmits<{ saved: []; dirty: [value: boolean]; pending: [value: boolean] }>()
const session = useSessionStore()
const value = ref<RoleDataScope>(),
  mode = ref<DepartmentScopeType>('all'),
  selected = ref<string[]>([])
const loading = ref(false),
  busy = ref(false),
  uncertain = ref(false),
  conflict = ref(false),
  error = ref(''),
  notice = ref('')
let sequence = 0,
  controller = new AbortController(),
  baseline = '',
  key = newIdempotencyKey()
const serialized = computed(() => JSON.stringify([mode.value, [...selected.value].sort()]))
const choices = computed(() => departmentTree(value.value?.departments ?? []))
const disabled = computed(
  () => busy.value || uncertain.value || conflict.value || !value.value?.editable,
)
watch(serialized, (next) => emit('dirty', Boolean(value.value) && next !== baseline), {
  flush: 'sync',
})
watch(busy, (next) => emit('pending', next), { flush: 'sync' })
watch(mode, (next) => {
  if (next !== 'custom') selected.value = []
})
async function load(): Promise<void> {
  const current = ++sequence
  controller.abort()
  controller = new AbortController()
  value.value = undefined
  error.value = ''
  notice.value = ''
  uncertain.value = conflict.value = false
  emit('dirty', false)
  if (!session.companyId || !session.can('role:assign_permission')) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await roleDataScope(props.roleId, controller.signal)
    if (current !== sequence) return
    mode.value = result.scopeType
    selected.value = [...result.departmentIds]
    baseline = serialized.value
    key = newIdempotencyKey()
    value.value = result
  } catch (cause: unknown) {
    if (current === sequence && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === sequence) loading.value = false
  }
}
function reload(): void {
  if (
    value.value &&
    serialized.value !== baseline &&
    !window.confirm('修改尚未保存，确定重新加载部门范围？')
  )
    return
  void load()
}
async function save(): Promise<void> {
  if (!value.value?.editable || busy.value || conflict.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    value.value = await saveRoleDataScope(
      props.roleId,
      {
        scopeType: mode.value,
        departmentIds: mode.value === 'custom' ? [...selected.value] : [],
        version: value.value.version,
      },
      key,
    )
    uncertain.value = false
    baseline = serialized.value
    key = newIdempotencyKey()
    emit('dirty', false)
    emit('saved')
    notice.value = '当前公司的角色部门范围已保存'
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
watch(
  [
    () => props.roleId,
    () => session.companyId,
    () => session.user?.id,
    () => session.can('role:assign_permission'),
  ],
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++sequence
  controller.abort()
})
</script>

<template>
  <section aria-label="角色部门数据范围" class="department-scope-editor">
    <h2>部门数据范围</h2>
    <p class="muted">
      当前公司独立配置。多个角色的部门范围取并集，再与公司、仓库权限取交集。未配置角色默认为全部。
    </p>
    <p v-if="!session.companyId">请选择公司后配置部门范围。</p>
    <p v-if="loading" role="status">正在加载部门范围…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <el-form v-if="value" label-position="top" @submit.prevent="save">
      <fieldset :disabled="disabled" class="form-fieldset">
        <el-form-item label="部门范围" for="role-department-scope">
          <el-select id="role-department-scope" v-model="mode" :disabled="disabled">
            <el-option label="全部部门" value="all" /><el-option label="本部门" value="dept" />
            <el-option label="本部门及下级" value="dept_and_children" /><el-option
              label="仅本人"
              value="self"
            /><el-option label="自定义部门" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'custom'" label="可访问部门" for="role-scope-departments">
          <el-tree-select
            id="role-scope-departments"
            v-model="selected"
            :data="choices"
            :props="{ label: 'name' }"
            node-key="id"
            multiple
            show-checkbox
            check-strictly
            filterable
            :disabled="disabled"
          />
        </el-form-item>
      </fieldset>
      <p v-if="!value.editable">仅当前公司拥有全部部门范围的角色管理员可以修改。</p>
      <p v-if="mode === 'custom' && !selected.length">
        未选部门时，此角色不授予任何部门记录；其他角色仍可能授予访问范围。
      </p>
      <p v-if="uncertain">保存结果尚未确认，请使用原内容重试。</p>
      <p v-if="conflict">配置已更新，请重新加载后再修改。</p>
      <div class="department-scope-actions">
        <el-button
          v-if="value.editable"
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="conflict"
          >{{ uncertain ? '重试原范围保存' : '保存部门范围' }}</el-button><el-button
          :disabled="busy"
          @click="reload"
          >重新加载部门范围</el-button
        >
      </div>
    </el-form>
    <el-button v-else-if="error" @click="load">重试范围加载</el-button>
  </section>
</template>

<style scoped>
.department-scope-editor {
  min-width: 0;
}
.department-scope-editor h2 {
  font-size: 16px;
}
.el-select {
  width: 100%;
}
.department-scope-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
