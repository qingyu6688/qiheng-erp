<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { record, allRecords, writeRecord } from '../api/admin'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { fieldValues, editorPayload, optionsFrom } from '../utils/admin'
import { useSessionStore } from '../stores/session'
import type { DataRow, FormField, ResourceSpec, SelectOption } from '../types/admin'
import DynamicFields from './DynamicFields.vue'
import { customFields } from '../api/platform-rules'
import type { CustomField, FieldValues } from '../api/platform-rules'
import DataFields from './DataFields.vue'
import RecordFrame from './RecordFrame.vue'
import PaymentTermsField from './PaymentTermsField.vue'
import { readPaymentTerm } from '../utils/payment-terms'
import type { PaymentTerm } from '../types/payment-terms'

const props = defineProps<{ spec: ResourceSpec; row?: DataRow; inline?: boolean }>()
const emit = defineEmits<{
  close: []
  saved: []
  dirty: [value: boolean]
  pending: [value: boolean]
}>()
const session = useSessionStore()
const fields = computed(() =>
  props.spec.fields.filter((f) => !f.permission || session.can(f.permission)),
)
const entity =
  props.spec.path === 'skus' ? 'sku' : props.spec.path === 'partners' ? 'business_partner' : null
const extended = ref<CustomField[]>([]),
  ext = ref<FieldValues>({ ...(props.row?.extJson as FieldValues | undefined) })
const values = reactive(fieldValues(fields.value, props.row))
const paymentTerms = reactive<Record<string, PaymentTerm>>({
  customer: readPaymentTerm((props.row?.paymentTerms as Record<string, unknown> | undefined)?.customer),
  supplier: readPaymentTerm((props.row?.paymentTerms as Record<string, unknown> | undefined)?.supplier),
})
const options = reactive<Record<string, SelectOption[]>>({})
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const conflict = ref(false)
const uncertain = ref(false)
const key = newIdempotencyKey()
function payload(): Record<string, unknown> {
  return {
    ...editorPayload(fields.value, values, props.row),
    ...(props.spec.path === 'partners' ? { paymentTerms: Object.fromEntries(Object.entries(paymentTerms).filter(([role]) => Array.isArray(values.roles) && values.roles.includes(role))) } : {}),
    ...(extended.value.length ? { extJson: ext.value } : {}),
  }
}
let baseline = JSON.stringify(payload())
watch(
  () => JSON.stringify(payload()),
  (value) => emit('dirty', value !== baseline),
  { flush: 'sync' },
)
watch(busy, (value) => emit('pending', value), { flush: 'sync' })
function markClean(): void {
  baseline = JSON.stringify(payload())
  emit('dirty', false)
}

onMounted(async () => {
  try {
    if (entity) {
      try {
        extended.value = await customFields(entity)
      } catch (cause: unknown) {
        if (!isCancelled(cause)) error.value = messageOf(cause)
      }
    }
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
    markClean()
    loading.value = false
  }
})

function empty(field: FormField): boolean {
  const value = values[field.key]
  return value == null || value === '' || (Array.isArray(value) && !value.length)
}

async function save(): Promise<void> {
  if (busy.value || loading.value || conflict.value || uncertain.value) return
  const missing = fields.value.find(
    (f) =>
      f.required &&
      !(props.row && f.createOnly) &&
      !(entity && !props.row && f.key === 'code') &&
      empty(f),
  )
  if (missing) {
    error.value = '请填写' + missing.label
    return
  }
  busy.value = true
  error.value = ''
  try {
    const body = payload()
    await writeRecord(
      props.spec.path + (props.row ? '/' + props.row.id : ''),
      body,
      props.row ? 'PUT' : 'POST',
      props.spec.company,
      props.row && !props.spec.idempotentUpdate ? undefined : key,
    )
    markClean()
    emit('saved')
  } catch (cause: unknown) {
    if (isCancelled(cause)) return
    if (cause instanceof ApiError) {
      conflict.value = cause.code === 'VERSION_CONFLICT'
      uncertain.value =
        (!props.row || Boolean(props.spec.idempotentUpdate)) &&
        (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
    }
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function checkResult(): Promise<void> {
  busy.value = true
  try {
    const operation =
      props.row && props.spec.idempotentUpdate
        ? props.spec.permission + '.update'
        : (props.spec.createOperation ?? props.spec.permission + '.create')
    const result = await record(
      'command-results?operation=' + operation + '&key=' + key,
      props.spec.company,
    )
    if (result.status === 'succeeded') {
      markClean()
      emit('saved')
    } else error.value = '操作正在处理，请稍后再次查询。'
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
  <RecordFrame
    :inline="inline"
    :busy="busy"
    :title="(row ? '编辑' : '新建') + spec.singular"
    width="560px"
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
      <template v-if="spec.path === 'partners'">
        <PaymentTermsField v-if="Array.isArray(values.roles) && values.roles.includes('customer')" v-model="paymentTerms.customer!" label="客户账期" inherit-label="使用公司默认账期" />
        <PaymentTermsField v-if="Array.isArray(values.roles) && values.roles.includes('supplier')" v-model="paymentTerms.supplier!" label="供应商账期" inherit-label="使用公司默认账期" />
      </template>
      <el-form v-if="extended.length" label-position="top"
        ><h3>其他信息</h3>
        <DynamicFields v-model="ext" :fields="extended" prefix="master-extra-"
      /></el-form>
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
  </RecordFrame>
</template>
