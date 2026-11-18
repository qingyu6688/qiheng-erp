<script setup lang="ts">
defineProps<{ expanded: boolean; expandable: boolean; controlsId: string }>()
const emit = defineEmits<{ submit: []; 'update:expanded': [value: boolean] }>()
</script>

<template>
  <form class="filters document-query" @submit.prevent="emit('submit')">
    <div class="document-query-main">
      <slot />
      <div class="document-filter-actions">
        <slot name="actions" />
        <el-button
          v-if="expandable"
          :aria-expanded="expanded"
          :aria-controls="controlsId"
          @click="emit('update:expanded', !expanded)"
        >
          {{ expanded ? '收起筛选' : '展开筛选' }}
        </el-button>
      </div>
    </div>
    <div v-show="expanded && expandable" :id="controlsId" class="document-query-more">
      <slot name="advanced" />
    </div>
  </form>
</template>
