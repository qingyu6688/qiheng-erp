<script setup lang="ts">
import { ref } from 'vue'
import { pairWarehouseDevice } from '../api/device'
import { messageOf } from '../api/http'

const name = ref('')
const pairing = ref<Awaited<ReturnType<typeof pairWarehouseDevice>>>()
const error = ref('')
const busy = ref(false)

async function issue(): Promise<void> {
  if (!name.value.trim() || busy.value) return
  busy.value = true
  pairing.value = undefined
  error.value = ''
  try {
    pairing.value = await pairWarehouseDevice(name.value.trim())
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel device-pairing" aria-labelledby="device-heading">
    <h2 id="device-heading">配对仓储设备</h2>
    <p>在 PDA 的配对登录页输入一次性配对码。管理员可在「在线用户」中强制下线。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="issue">
      <el-form-item label="设备名称" for="pair-device-name">
        <el-input id="pair-device-name" v-model="name" maxlength="80" autocomplete="off" />
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!name.trim()">
        生成配对码
      </el-button>
    </el-form>
    <div v-if="pairing" class="pairing-result" aria-live="polite">
      <label for="warehouse-pair-code">一次性配对码</label>
      <el-input id="warehouse-pair-code" readonly :model-value="pairing.pairingCode" />
      <p>有效时间最长 5 分钟。</p>
      <el-button @click="pairing = undefined">隐藏配对码</el-button>
    </div>
  </section>
</template>

<style scoped>
.device-pairing {
  margin-top: 16px;
}
.device-pairing .el-form,
.pairing-result {
  max-width: 440px;
}
.pairing-result {
  margin-top: 16px;
}
</style>
