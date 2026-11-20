<script setup lang="ts">
import type { WikiReviewContent } from '@/types/content'

/** Wiki 修订审核示意：删除线是被替换的旧说法，绿色是 AI 起草的新说法，进入视口时高亮刷入 */
defineProps<{ wiki: WikiReviewContent }>()
</script>

<template>
  <div class="wiki-mock" :aria-label="wiki.ariaLabel" data-reveal="scale">
    <div class="top"><b>{{ wiki.title }}</b><span class="tagx">{{ wiki.tag }}</span><span class="meta">{{ wiki.meta }}</span></div>
    <div class="body">
      <h4>{{ wiki.heading }}</h4>
      <p class="wiki-text">{{ wiki.before }}<sup>[1]</sup>{{ wiki.sep }}<span class="diff-del">{{ wiki.removed }}</span><span class="diff-add">{{ wiki.added }}<sup>[2]</sup>{{ wiki.end }}</span></p>
      <p class="refs">{{ wiki.refs }}</p>
    </div>
    <div class="foot">
      <button class="btn btn-sm" type="button">{{ wiki.reject }}</button>
      <button class="btn btn-sm btn-primary" type="button">{{ wiki.publish }}</button>
    </div>
  </div>
</template>
