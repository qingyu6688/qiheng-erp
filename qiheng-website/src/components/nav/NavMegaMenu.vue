<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { NavEntry, SiteChrome } from '@/types/content'
import NavMenuItem from './NavMenuItem.vue'

defineProps<{ entry: NavEntry; mega: SiteChrome['mega']; demoLabel: string }>()
</script>

<template>
  <div :id="`panel-${entry.key}`" class="nav-panel mega-panel">
    <div v-for="group in entry.groups" :key="group.title" class="mega-col">
      <h5>{{ group.title }}</h5>
      <NavMenuItem v-for="item in group.items" :key="item.href" :item="item" />
    </div>
    <SmartLink class="mega-feature" :href="mega.href">
      <span class="mf-eyebrow"><AppIcon name="spark" />{{ mega.eyebrow }}</span>
      <b>{{ mega.title }}</b>
      <span class="mf-desc">{{ mega.desc }}</span>
      <span class="mf-chips"><i v-for="chip in mega.chips" :key="chip">{{ chip }}</i></span>
      <span class="mf-more">{{ mega.more }}<AppIcon name="arrow" /></span>
    </SmartLink>
    <div class="mega-foot">
      <span>{{ mega.foot }}</span>
      <span>
        <SmartLink :href="mega.all.href">{{ mega.all.label }}<AppIcon name="arrow" /></SmartLink>
        <SmartLink href="/demo">{{ demoLabel }}<AppIcon name="arrow" /></SmartLink>
      </span>
    </div>
  </div>
</template>
