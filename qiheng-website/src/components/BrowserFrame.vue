<script setup lang="ts">
/** 带浏览器窗口栏的截图外框 */
withDefaults(defineProps<{ url: string; label?: string; loading?: boolean }>(), { label: undefined, loading: false })
</script>

<template>
  <div class="bezel">
    <figure class="shot" :aria-label="label">
      <div class="bar"><i></i><i></i><i></i><span>{{ url }}</span></div>
      <div class="frame" :class="{ 'is-loading': loading }"><slot /></div>
    </figure>
  </div>
</template>
