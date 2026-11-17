<script setup lang="ts">
import { inject } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { openAiDocument } from '../hooks/ai-document'
import { useSessionStore } from '../stores/session'
import type { AiDocumentContext } from '../types/ai'

const props = defineProps<{ document: AiDocumentContext }>()
const open = inject(openAiDocument)
const session = useSessionStore()
</script>

<template>
  <el-button v-if="open && session.can('ai:use')" :icon="ChatDotRound" @click="open(props.document)"
    >问 AI</el-button
  >
</template>
