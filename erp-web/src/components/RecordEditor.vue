<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { record, allRecords, writeRecord } from '../api/admin'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { fieldValues, editorPayload, optionsFrom } from '../utils/admin'
import { useSessionStore } from '../stores/session'
import type { DataRow, FormField, ResourceSpec, SelectOption } from '../types/admin'
import DataFields from './DataFields.vue'

const props = defineProps<{ spec: ResourceSpec; row?: DataRow }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const session = useSessionStore()
const fields = computed(() =>
  props.spec.fields.filter((f) => !f.permission || session.can(f.permission)),
)
const values = reactive(fieldValues(fields.value, props.row))
const options = reactive<Record<string, SelectOption[]>>({})
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const conflict = ref(false)
const uncertain = ref(false)
const key = newIdempotencyKey()

onMounted(async () => {
  try {
    const sources = fields.value.filter(
      (f) => f.source && (!f.sourcePermission || session.can(f.sourcePermission)),
    )
    await Promise.all(
      sources.map(async (field) => {
        const data = await allRecords(field.source!)
        options[field.key] = optionsFrom(data).filter(
          (o) => !(field.key === 'parentId' && o.value === props.row?.id),
        )
      }),
    )
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
})

function empty(field: FormField): boolean {
  const value = values[field.key]
  return value == null || value === '' || (Array.isArray(value) && !value.length)
}

async function save(): Promise<void> {
  if (busy.value || loading.value || conflict.value || uncertain.value) return
  const missing = fields.value.find((f) => f.required && !(props.row && f.createOnly) && empty(f))
  if (missing) {
    error.value = '请填写' + missing.label
    return
  }
  busy.value = true
  error.value = ''
  try {
    const body = editorPayload(fields.value, values, props.row)
    await writeRecord(
      props.spec.path + (props.row ? '/' + props.row.id : ''),
      body,
      props.row ? 'PUT' : 'POST',
      props.spec.company,
      props.row ? undefined : key,
    )
    emit('saved')
  } catch (cause: unknown) {
    if (isCancelled(cause)) return
    if (cause instanceof ApiError) {
      conflict.value = cause.code === 'VERSION_CONFLICT'
      uncertain.value = !props.row && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
    }
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function checkResult(): Promise<void> {
  busy.value = true
  try {
    const operation = props.spec.createOperation ?? props.spec.permission + '.create'
    const result = await record(
      'command-results?operation=' + operation + '&key=' + key,
      props.spec.company,
    )
    if (result.status === 'succeeded') emit('saved')
    else error.value = '操作正在处理，请稍后再次查询。'
  } catch (cause: unknown) {
    if (cause instanceof ApiError && cause.status === 404) {
      uncertain.value = false
      error.value = '尚无成功记录，可使用原请求重试。'
    } else error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="(row ? '编辑' : '新建') + spec.singular"
    width="560px"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @close="emit('close')"
  >
    <fieldset :disabled="busy || loading || uncertain || conflict" class="form-fieldset">
      <DataFields
        :fields="fields"
        :values="values"
        :options="options"
        :editing="Boolean(row)"
        :disabled="busy || loading || uncertain || conflict"
        @change="(k, v) => (values[k] = v)"
      />
    </fieldset>
    <p v-if="loading" role="status">正在加载可选资料…</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <p v-if="conflict">资料已有更新。关闭窗口并刷新后重新编辑。</p>
    <p v-if="uncertain">请求结果待确认，请先查询结果。</p>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">{{
        conflict ? '关闭' : '取消'
      }}</el-button>
      <el-button v-if="uncertain" type="primary" :loading="busy" @click="checkResult"
        >查询结果</el-button
      >
      <el-button v-else type="primary" :disabled="loading || conflict" :loading="busy" @click="save"
        >保存</el-button
      >
    </template>
  </el-dialog>
</template>
