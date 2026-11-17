<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  removeSensitiveWord,
  saveSensitiveWord,
  sensitiveWords,
  type SensitiveWord,
} from '../../api/customer-service'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const rows = ref<SensitiveWord[]>([])
const active = ref<SensitiveWord>()
const busy = ref(false)
const error = ref('')
const form = reactive({ word: '', enabled: true })

async function work(action?: () => Promise<unknown>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (action) await action()
    rows.value = await sensitiveWords()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function edit(row?: SensitiveWord): void {
  active.value = row
  form.word = row?.word ?? ''
  form.enabled = row?.enabled ?? true
}

function save(): void {
  const row = active.value
  void work(async () => {
    await saveSensitiveWord({ ...form, version: row?.version }, row?.id)
    edit()
  })
}

function remove(row: SensitiveWord): void {
  if (window.confirm('确认删除此敏感词？'))
    void work(() => removeSensitiveWord(row.id, row.version))
}

onMounted(() => work())
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>敏感词</h1>
      <p>维护当前公司的门户客服过滤词。</p>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel">
    <el-form v-if="session.can('portal_sensitive_word:manage')" inline @submit.prevent="save">
      <el-form-item label="敏感词"
        ><el-input v-model.trim="form.word" maxlength="100"
      /></el-form-item>
      <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      <el-form-item
        ><el-button type="primary" native-type="submit" :disabled="!form.word || busy">{{
          active ? '保存修改' : '新增敏感词'
        }}</el-button
        ><el-button v-if="active" @click="edit()">取消</el-button></el-form-item
      >
    </el-form>
    <el-table :data="rows" :empty-text="busy ? '正在加载…' : '暂无敏感词'" row-key="id">
      <el-table-column prop="word" label="词语" min-width="200" />
      <el-table-column label="状态" width="110"
        ><template #default="{ row }">{{
          row.enabled ? '启用' : '停用'
        }}</template></el-table-column
      >
      <el-table-column v-if="session.can('portal_sensitive_word:manage')" label="操作" width="140"
        ><template #default="{ row }"
          ><el-button link type="primary" :disabled="busy" @click="edit(row)">编辑</el-button
          ><el-button link type="danger" :disabled="busy" @click="remove(row)"
            >删除</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
</template>
