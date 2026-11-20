<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { MenuItem } from '@/types/content'

const props = defineProps<{ item: MenuItem }>()
const external = computed(() => /^https?:\/\//.test(props.item.href))
</script>

<template>
  <SmartLink class="menu-item" :href="item.href" :hreflang="item.hreflang">
    <span class="mi"><AppIcon :name="item.icon" /></span>
    <span>
      <b>{{ item.label }}<AppIcon v-if="external" name="ext" class="ext" /></b>
      <small>{{ item.desc }}</small>
    </span>
  </SmartLink>
</template>
