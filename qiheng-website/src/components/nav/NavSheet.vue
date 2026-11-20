<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import { REPO } from '@/config/site'
import type { MenuItem, NavKey, SiteChrome } from '@/types/content'

/** 1200px 以下的分组抽屉：顶部是常用入口，下面按分组列出全部链接 */
const props = defineProps<{ chrome: SiteChrome; pageKey?: NavKey }>()
const emit = defineEmits<{ navigate: [] }>()

const tops = computed(() => props.chrome.nav.filter((entry) => entry.href))
const groups = computed(() =>
  props.chrome.nav
    .filter((entry) => !entry.href)
    .map((entry) => ({
      key: entry.key,
      label: entry.label,
      mega: Boolean(entry.groups),
      items: entry.groups ? entry.groups.flatMap((group) => group.items) : (entry.items ?? []),
    })),
)

// 抽屉打开时链接依次出现，用全局序号控制延迟
const order = computed(() => {
  const map = new Map<string, number>()
  let index = tops.value.length
  groups.value.forEach((group) => group.items.forEach((item: MenuItem) => map.set(item.href + item.label, index++)))
  return map
})
</script>

<template>
  <div id="nav-sheet" class="nav-sheet">
    <div class="sheet-tops">
      <SmartLink
        v-for="(entry, i) in tops"
        :key="entry.key"
        class="sheet-top"
        :href="entry.href ?? '/'"
        :aria-current="entry.key === pageKey ? 'page' : undefined"
        :style="{ '--i': i }"
        @click="emit('navigate')"
      >
        {{ entry.label }}<span v-if="entry.badge" class="nav-badge">{{ entry.badge }}</span><AppIcon name="arrow" />
      </SmartLink>
    </div>
    <div v-for="group in groups" :key="group.key" class="sheet-group">
      <h5>{{ group.label }}</h5>
      <div class="sheet-grid" :class="{ two: group.mega }">
        <SmartLink
          v-for="item in group.items"
          :key="item.href + item.label"
          class="sheet-link"
          :href="item.href"
          :hreflang="item.hreflang"
          :style="{ '--i': order.get(item.href + item.label) }"
          @click="emit('navigate')"
        >
          <span class="mi"><AppIcon :name="item.icon" /></span>{{ item.label }}
        </SmartLink>
      </div>
    </div>
    <div class="sheet-cta">
      <a class="btn" :href="REPO" rel="noopener"><AppIcon name="github" />{{ chrome.github }}</a>
      <SmartLink class="btn" :href="chrome.lang.href" :hreflang="chrome.lang.hreflang" :lang="chrome.lang.hreflang" @click="emit('navigate')">
        <AppIcon name="globe" />{{ chrome.lang.name }}
      </SmartLink>
    </div>
  </div>
</template>
