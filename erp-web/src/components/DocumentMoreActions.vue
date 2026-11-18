<script setup lang="ts">
import {
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
} from 'element-plus/es/components/dropdown/index.mjs'
import 'element-plus/theme-chalk/el-dropdown.css'

defineProps<{ actions: string[]; label: (action: string) => string }>()
defineEmits<{ choose: [action: string] }>()
</script>

<template>
  <el-dropdown v-if="actions.length" trigger="click" @command="$emit('choose', $event)">
    <el-button>更多操作</el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="action in actions" :key="action" :command="action">
          {{ label(action) }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
