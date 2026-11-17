<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { brands, saveBrand } from '../api/supply-chain'
import type { Brand, BrandInput } from '../api/supply-chain'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const rows = ref<Brand[]>([])
const keyword = ref('')
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const form = ref<BrandInput>()

async function load(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    rows.value = await brands(keyword.value.trim())
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
function edit(row?: Brand): void {
  form.value = row
    ? {
        id: row.id,
        version: row.version,
        code: row.code,
        name: row.name,
        sortNo: row.sortNo,
        enabled: row.status === 'active',
      }
    : { code: '', name: '', sortNo: 0, enabled: true }
}
async function save(): Promise<void> {
  if (!form.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await saveBrand(form.value)
    form.value = undefined
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>

<template>
  <header class="page-heading">
    <h1>品牌</h1>
    <el-button v-if="session.can('brand:manage')" type="primary" @click="edit()"
      >新增品牌</el-button
    >
  </header>
  <el-alert v-if="error && !form" type="error" :title="error" :closable="false" />
  <section class="panel table-panel">
    <form class="filters" @submit.prevent="load">
      <el-input v-model="keyword" aria-label="品牌关键词" placeholder="品牌编码或名称" clearable />
      <el-button native-type="submit" :loading="busy">查询</el-button>
    </form>
    <el-table v-loading="busy" :data="rows" row-key="id" empty-text="暂无符合条件的品牌">
      <el-table-column prop="code" label="编码" min-width="140" />
      <el-table-column prop="name" label="品牌名称" min-width="220" />
      <el-table-column prop="sortNo" label="排序" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">{{ row.status === 'active' ? '启用' : '停用' }}</template>
      </el-table-column>
      <el-table-column v-if="session.can('brand:manage')" label="操作" width="100">
        <template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">编辑</el-button></template
        >
      </el-table-column>
    </el-table>
  </section>
  <el-dialog
    :model-value="!!form"
    :title="form?.id ? '编辑品牌' : '新增品牌'"
    @closed="form = undefined"
    @update:model-value="form = undefined"
  >
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-form v-if="form" label-position="top" @submit.prevent="save">
      <el-form-item label="品牌编码"
        ><el-input v-model="form.code" maxlength="32" required
      /></el-form-item>
      <el-form-item label="品牌名称"
        ><el-input v-model="form.name" maxlength="100" required
      /></el-form-item>
      <el-form-item label="排序"
        ><el-input-number v-model="form.sortNo" :min="0" :max="2147483647"
      /></el-form-item>
      <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving">保存</el-button>
    </el-form>
  </el-dialog>
</template>
