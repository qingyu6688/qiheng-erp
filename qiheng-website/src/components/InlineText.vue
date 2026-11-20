<script setup lang="ts">
import { computed } from 'vue'
import { splitInlineCode } from '@/utils/text'

/** 文案里用反引号标出的命令、变量名渲染成行内代码 */
const props = defineProps<{ text: string }>()
const segments = computed(() => splitInlineCode(props.text))
</script>

<template>
  <template v-for="(segment, i) in segments" :key="i"><code v-if="segment.code">{{ segment.text }}</code><template v-else>{{ segment.text }}</template></template>
</template>
