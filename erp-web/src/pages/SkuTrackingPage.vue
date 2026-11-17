<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { brands, saveSkuTracking, skuTracking } from '../api/supply-chain'
import type { Brand, SkuTracking } from '../api/supply-chain'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const route = useRoute()
const item = ref<SkuTracking>()
const choices = ref<Brand[]>([])
const busy = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const writable = computed(() => session.can('sku_tracking:manage') && session.can('sku:update'))

onMounted(async () => {
  try {
    const id = String(route.params.id)
    item.value = await skuTracking(id)
    if (session.can('brand:view')) choices.value = await brands()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
})
async function save(): Promise<void> {
  if (!item.value || saving.value) return
  saving.value = true
  error.value = notice.value = ''
  const {
    id,
    weightKg,
    brandId,
    batchManaged,
    serialManaged,
    shelfLifeDays,
    nearExpiryDays,
    issueStrategy,
    version,
  } = item.value
  try {
    item.value = await saveSkuTracking(id, {
      weightKg: weightKg || null,
      brandId: brandId || null,
      batchManaged,
      serialManaged,
      shelfLifeDays,
      nearExpiryDays,
      issueStrategy,
      version,
    })
    notice.value = '商品供应链设置已保存'
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    saving.value = false
  }
}
function changeBatch(value: boolean | string | number): void {
  if (value || !item.value) return
  item.value.shelfLifeDays = 0
  item.value.nearExpiryDays = 0
  if (item.value.issueStrategy === 'fefo') item.value.issueStrategy = 'fifo'
}
</script>

<template>
  <header class="page-heading">
    <h1>SKU 供应链设置</h1>
    <router-link to="/skus" class="text-link">返回商品列表</router-link>
  </header>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <el-alert v-if="notice" type="success" :title="notice" :closable="false" />
  <section v-loading="busy" class="panel tracking-form">
    <el-form
      v-if="item"
      label-position="top"
      :disabled="!writable || saving"
      @submit.prevent="save"
    >
      <h2>{{ item.code }} {{ item.name }}</h2>
      <el-form-item v-if="session.can('brand:view')" label="品牌">
        <el-select v-model="item.brandId" filterable clearable>
          <el-option
            v-for="brand in choices"
            :key="brand.id"
            :value="brand.id"
            :label="brand.name"
            :disabled="brand.status !== 'active'"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="单件重量（千克）"
        ><el-input v-model="item.weightKg" inputmode="decimal" clearable
      /></el-form-item>
      <el-form-item label="批次管理"
        ><el-switch v-model="item.batchManaged" @change="changeBatch"
      /></el-form-item>
      <el-form-item label="序列号管理"><el-switch v-model="item.serialManaged" /></el-form-item>
      <el-form-item v-if="item.batchManaged" label="保质期（天）">
        <el-input-number
          v-model="item.shelfLifeDays"
          :min="0"
          :max="36500"
          :step="1"
          :precision="0"
        />
      </el-form-item>
      <el-form-item v-if="item.batchManaged" label="临期提前天数">
        <el-input-number
          v-model="item.nearExpiryDays"
          :min="0"
          :max="36500"
          :step="1"
          :precision="0"
        />
      </el-form-item>
      <el-form-item label="实物出库策略">
        <el-select v-model="item.issueStrategy">
          <el-option label="先进先出" value="fifo" />
          <el-option v-if="item.batchManaged" label="近效期先出" value="fefo" />
          <el-option label="手工选择" value="manual" />
        </el-select>
      </el-form-item>
      <el-button v-if="writable" type="primary" native-type="submit" :loading="saving"
        >保存设置</el-button
      >
    </el-form>
  </section>
</template>

<style scoped>
.tracking-form {
  max-width: 620px;
  padding: 20px;
}
</style>
