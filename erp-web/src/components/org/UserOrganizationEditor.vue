<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ElTreeSelect } from 'element-plus/es/components/tree-select/index.mjs'
import { assignOrganization, assignmentOptions, userOrganization } from '../../api/org'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { departmentTree } from '../../types/org'
import type { AssignmentOptions, UserOrganization } from '../../types/org'

const props = defineProps<{ userId: string }>()
const emit = defineEmits<{ saved: []; dirty: [value: boolean]; pending: [value: boolean] }>()
const session = useSessionStore()
const value = ref<UserOrganization>()
const options = ref<AssignmentOptions>({ departments: [], posts: [] })
const departmentIds = ref<string[]>([]),
  primaryId = ref(''),
  postIds = ref<string[]>([])
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  uncertain = ref(false)
let revision = 0,
  controller = new AbortController(),
  key = newIdempotencyKey(),
  baseline = ''
const tree = computed(() => departmentTree(options.value.departments))
const primaryOptions = computed(() =>
  options.value.departments.filter((row) => departmentIds.value.includes(row.id)),
)
const serialized = computed(() =>
  JSON.stringify({
    departmentIds: [...departmentIds.value].sort(),
    primaryId: primaryId.value,
    postIds: [...postIds.value].sort(),
  }),
)
function reload(): void {
  if (serialized.value !== baseline && !window.confirm('修改尚未保存，确定重新加载组织配置？'))
    return
  void load()
}
watch(
  serialized,
  (next) => {
    emit('dirty', Boolean(value.value) && next !== baseline)
  },
  { flush: 'sync' },
)
watch(departmentIds, (ids) => {
  if (!ids.includes(primaryId.value)) primaryId.value = ids[0] ?? ''
})
watch(busy, (next) => emit('pending', next), { flush: 'sync' })
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  value.value = undefined
  options.value = { departments: [], posts: [] }
  departmentIds.value = []
  primaryId.value = ''
  postIds.value = []
  emit('dirty', false)
  notice.value = ''
  error.value = ''
  uncertain.value = false
  if (!session.companyId || !session.can('user:update')) return
  loading.value = true
  try {
    const [configuration, choices] = await Promise.all([
      userOrganization(props.userId, controller.signal),
      assignmentOptions(controller.signal),
    ])
    if (current !== revision) return
    options.value = choices
    departmentIds.value = configuration.departments.map((row) => row.id)
    primaryId.value = configuration.departments.find((row) => row.primary)?.id ?? ''
    postIds.value = [...configuration.postIds]
    baseline = serialized.value
    key = newIdempotencyKey()
    value.value = configuration
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function save(): Promise<void> {
  if (!value.value || busy.value) return
  if (departmentIds.value.length && !primaryId.value) {
    error.value = '请选择主部门'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const next = await assignOrganization(
      props.userId,
      {
        version: value.value.version,
        departments: departmentIds.value.map((id) => ({ id, primary: id === primaryId.value })),
        postIds: [...postIds.value],
      },
      key,
    )
    value.value = next
    baseline = serialized.value
    key = newIdempotencyKey()
    uncertain.value = false
    emit('dirty', false)
    emit('saved')
    notice.value = '当前公司的部门与岗位已保存'
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      uncertain.value =
        cause instanceof ApiError && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
    }
  } finally {
    busy.value = false
  }
}
watch(
  () => [props.userId, session.companyId, session.can('user:update')],
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  controller.abort()
})
</script>

<template>
  <section class="organization-editor" aria-label="用户部门与岗位">
    <h2>部门与岗位</h2>
    <p class="muted">仅保存当前公司任职，访问权限仍由原数据范围控制。</p>
    <p v-if="!session.companyId">请选择公司后配置任职。</p>
    <p v-if="loading" role="status">正在加载组织配置…</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-alert v-if="notice" type="success" :title="notice" :closable="false" />
    <el-form v-if="value" label-position="top" @submit.prevent="save">
      <fieldset :disabled="busy || uncertain" class="form-fieldset">
        <el-form-item label="所属部门" for="organization-departments">
          <el-tree-select
            id="organization-departments"
            v-model="departmentIds"
            :data="tree"
            :props="{ label: 'name' }"
            node-key="id"
            multiple
            show-checkbox
            check-strictly
            filterable
            :disabled="busy || uncertain"
          />
        </el-form-item>
        <el-form-item label="主部门" for="organization-primary">
          <el-select
            id="organization-primary"
            v-model="primaryId"
            :disabled="busy || uncertain || !departmentIds.length"
          >
            <el-option
              v-for="row in primaryOptions"
              :key="row.id"
              :value="row.id"
              :label="row.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="任职岗位" for="organization-posts">
          <el-select
            id="organization-posts"
            v-model="postIds"
            multiple
            filterable
            :disabled="busy || uncertain"
          >
            <el-option
              v-for="row in options.posts"
              :key="row.id"
              :value="row.id"
              :label="row.name"
            />
          </el-select>
        </el-form-item>
      </fieldset>
      <p v-if="uncertain">保存结果尚未确认，可使用相同请求重试，或刷新核对。</p>
      <div class="organization-actions">
        <el-button native-type="submit" type="primary" :loading="busy">{{
          uncertain ? '重试原保存' : '保存部门与岗位'
        }}</el-button>
        <el-button :disabled="busy" @click="reload">重新加载组织配置</el-button>
      </div>
    </el-form>
    <el-button v-else-if="error" @click="load">重试组织加载</el-button>
  </section>
</template>

<style scoped>
.organization-editor {
  min-width: 0;
}
.organization-editor h2 {
  font-size: 16px;
  margin: 0 0 12px;
}
.organization-editor .el-select,
.organization-editor .el-tree-select {
  width: 100%;
}
.organization-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
