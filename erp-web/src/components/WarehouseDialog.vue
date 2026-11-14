<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  changeWarehouseStatus,
  commandResult,
  createWarehouse,
  updateWarehouse,
  warehouseDetail,
} from '../api/masterdata'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import type { Warehouse, WarehouseCommand, WarehouseInput } from '../types/warehouse'

type Mode = 'create' | 'update' | 'detail' | WarehouseCommand
const props = defineProps<{ mode: Mode; warehouse?: Warehouse }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const selected = ref(props.warehouse)
const input = reactive<WarehouseInput>({
  code: selected.value?.code ?? '',
  name: selected.value?.name ?? '',
  address: selected.value?.address ?? '',
})
const fields = reactive({ code: '', name: '' })
const error = ref('')
const notice = ref('')
const pending = ref(false)
const busy = ref(false)
const conflict = ref(false)
const key = newIdempotencyKey()
const title = computed(
  () =>
    ({
      create: '新建仓库',
      update: '编辑仓库',
      detail: '仓库详情',
      disable: '停用仓库',
      enable: '启用仓库',
    })[props.mode],
)
const formMode = computed(() => props.mode === 'create' || props.mode === 'update')
const operation = 'warehouse.' + props.mode

function close(): void {
  if (!busy.value) emit('close')
}
function validate(): boolean {
  fields.code = input.code.trim() ? '' : '请输入仓库编码。'
  fields.name = input.name.trim() ? '' : '请输入仓库名称。'
  return !fields.code && !fields.name
}

async function verifyResult(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const result = await commandResult(operation, key)
    if (result.status === 'succeeded') emit('saved')
    else notice.value = '操作正在处理，请稍后再查。'
  } catch (cause: unknown) {
    if (cause instanceof ApiError && cause.status === 404) {
      pending.value = false
      notice.value = '尚无成功记录。可以使用原请求重试。'
    } else if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function save(): Promise<void> {
  if (busy.value || pending.value || conflict.value) return
  if (formMode.value && !validate()) return
  busy.value = true
  error.value = notice.value = ''
  const data = {
    code: input.code.trim(),
    name: input.name.trim(),
    address: input.address?.trim() || null,
  }
  try {
    if (props.mode === 'create') await createWarehouse(data, key)
    else if (props.mode === 'update' && selected.value) {
      await updateWarehouse(selected.value.id, data, selected.value.version)
    } else if ((props.mode === 'disable' || props.mode === 'enable') && selected.value) {
      await changeWarehouseStatus(selected.value.id, props.mode, selected.value.version, key)
    } else return
    emit('saved')
  } catch (cause: unknown) {
    if (isCancelled(cause)) return
    if (cause instanceof ApiError && cause.code === 'VERSION_CONFLICT') conflict.value = true
    if (
      cause instanceof ApiError &&
      (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS') &&
      props.mode !== 'update'
    ) {
      pending.value = true
      notice.value = '请求结果待确认。请先查询处理结果。'
    } else error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function refreshConflict(): Promise<void> {
  if (!selected.value) return
  busy.value = true
  try {
    const fresh = await warehouseDetail(selected.value.id)
    if (!fresh.allowedActions.includes(props.mode)) {
      emit('saved')
      return
    }
    selected.value = fresh
    Object.assign(input, { code: fresh.code, name: fresh.name, address: fresh.address ?? '' })
    conflict.value = false
    error.value = ''
    notice.value = '已加载最新版本。请核对后再次提交。'
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function copyDraft(): Promise<void> {
  try {
    await navigator.clipboard.writeText(
      [input.code, input.name, input.address].filter(Boolean).join('\n'),
    )
    notice.value = '已复制当前填写的内容。'
  } catch {
    notice.value = '浏览器未授权复制，请选中字段内容后复制。'
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="title"
    width="480px"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @close="close"
  >
    <el-form v-if="formMode" id="warehouse-editor" label-position="top" @submit.prevent="save">
      <el-form-item label="仓库编码" for="warehouse-code" :error="fields.code" required>
        <el-input
          id="warehouse-code"
          v-model="input.code"
          maxlength="32"
          :disabled="busy || pending"
          placeholder="请输入仓库编码"
        />
      </el-form-item>
      <el-form-item label="仓库名称" for="warehouse-name" :error="fields.name" required>
        <el-input
          id="warehouse-name"
          v-model="input.name"
          maxlength="100"
          :disabled="busy || pending"
          placeholder="请输入仓库名称"
        />
      </el-form-item>
      <el-form-item label="地址" for="warehouse-address">
        <el-input
          id="warehouse-address"
          :model-value="input.address ?? ''"
          maxlength="200"
          type="textarea"
          :disabled="busy || pending"
          :rows="2"
          @update:model-value="input.address = $event"
        />
      </el-form-item>
    </el-form>
    <dl v-else-if="mode === 'detail' && selected" class="record-details">
      <dt>仓库编码</dt>
      <dd>{{ selected.code }}</dd>
      <dt>名称</dt>
      <dd>{{ selected.name }}</dd>
      <dt>地址</dt>
      <dd>{{ selected.address || '—' }}</dd>
      <dt>状态</dt>
      <dd>{{ selected.status === 'active' ? '启用' : '停用' }}</dd>
      <dt>版本</dt>
      <dd>{{ selected.version }}</dd>
    </dl>
    <p v-else-if="selected" class="confirm-text">
      {{
        mode === 'disable' ? '停用后，该仓库将无法用于新业务。' : '启用后，该仓库可用于新业务。'
      }}确认{{ mode === 'disable' ? '停用' : '启用' }}「{{ selected.name }}」？
    </p>
    <el-alert
      v-if="error"
      type="error"
      class="form-alert"
      :title="error"
      :closable="false"
      show-icon
    />
    <el-alert v-if="notice" type="info" class="form-alert" :title="notice" :closable="false" />
    <template #footer>
      <el-button :disabled="busy" @click="close">{{
        mode === 'detail' || pending ? '关闭' : '取消'
      }}</el-button>
      <template v-if="conflict">
        <el-button v-if="formMode" @click="copyDraft">复制我的修改</el-button>
        <el-button type="primary" :loading="busy" @click="refreshConflict">刷新查看</el-button>
      </template>
      <el-button v-else-if="pending" type="primary" :loading="busy" @click="verifyResult"
        >查询结果</el-button
      >
      <el-button
        v-else-if="mode !== 'detail'"
        :type="mode === 'disable' ? 'danger' : 'primary'"
        :plain="mode === 'disable'"
        :loading="busy"
        @click="save"
        >{{
          busy ? '提交中…' : formMode ? '保存' : '确认' + (mode === 'disable' ? '停用' : '启用')
        }}</el-button
      >
    </template>
  </el-dialog>
</template>
