<script setup lang="ts">
import { auxLabels } from '../types/gl'
import type { GlAux } from '../types/gl'
defineProps<{
  required: string[]
  options: Record<string, { id: string; name: string; code?: string }[]>
  disabled?: boolean
}>()
const model = defineModel<GlAux>({ required: true })
</script>
<template>
  <div class="aux-fields">
    <label v-for="type in required" :key="type"
      >{{ auxLabels[type]
      }}<el-select
        v-model="model[(type + 'Id') as keyof GlAux]"
        :disabled="disabled"
        filterable
        clearable
        :placeholder="'选择' + auxLabels[type]"
        ><el-option
          v-for="option in options[type] || []"
          :key="option.id"
          :value="option.id"
          :label="(option.code ? option.code + ' ' : '') + option.name" /></el-select
    ></label>
  </div>
</template>
<style scoped>
.aux-fields {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.aux-fields label {
  min-width: 160px;
  display: grid;
  gap: 4px;
  font-size: 12px;
}
</style>
