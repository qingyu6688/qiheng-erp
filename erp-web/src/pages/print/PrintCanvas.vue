<script setup lang="ts">
import { computed } from 'vue'
import type { PrintInk, PrintModel } from '../../types/print-design'

const props = defineProps<{ model: PrintModel; editing?: boolean }>()
const paper = computed(() => ({
  width: `${props.model.width}mm`,
  height: `${props.model.height}mm`,
}))
function position(item: PrintInk): Record<string, string | number> {
  return {
    left: `${item.x}mm`,
    top: `${item.y}mm`,
    width: `${item.w}mm`,
    height: `${item.h}mm`,
    textAlign: item.align,
    fontSize: `${item.fontSize}pt`,
    fontWeight: item.bold ? 700 : 400,
  }
}
</script>

<template>
  <div class="print-design-pages">
    <article
      v-for="(page, p) in model.pages"
      :key="p"
      class="print-design-sheet"
      :style="paper"
      :aria-label="`第 ${p + 1} 页`"
    >
      <img v-if="model.background" class="preprinted" :src="model.background" alt="套打对位背景" />
      <div v-if="editing" class="raster" aria-hidden="true" />
      <div
        v-for="(item, i) in page.items"
        :key="i"
        :class="['print-ink', `print-${item.type}`]"
        :style="position(item)"
      >
        <img v-if="item.type === 'image' && item.image" :src="model.assets?.[item.image]" alt="" />
        <template v-else-if="item.type === 'text'"
          ><div v-for="(line, index) in item.lines" :key="index">
            {{ line || '\u00a0' }}
          </div></template
        >
        <i v-else-if="item.type === 'line'" />
      </div>
      <slot v-if="editing && p === 0" />
    </article>
  </div>
</template>

<style scoped>
.print-design-sheet {
  position: relative;
  box-sizing: border-box;
  margin: 16px auto;
  background: #fff;
  color: #111;
  overflow: hidden;
  box-shadow: 0 1px 6px #17233f33;
  font-family: 'Microsoft YaHei', 'Noto Sans CJK SC', sans-serif;
}
.preprinted {
  position: absolute;
  width: 100%;
  height: 100%;
  object-fit: fill;
  inset: 0;
  opacity: 0.55;
}
.raster {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(#dce3eb 1px, transparent 1px),
    linear-gradient(90deg, #dce3eb 1px, transparent 1px);
  background-size: 5mm 5mm;
  opacity: 0.6;
}
.print-ink {
  position: absolute;
  overflow: hidden;
  box-sizing: border-box;
  pointer-events: none;
  line-height: 1.28;
}
.print-text {
  white-space: nowrap;
}
.print-rectangle {
  border: 0.55pt solid #111;
}
.print-image img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.print-line i {
  display: block;
  position: absolute;
  top: 50%;
  width: 100%;
  border-bottom: 0.55pt solid #111;
}
@media print {
  .preprinted {
    display: none;
  }
  .print-design-sheet {
    margin: 0;
    box-shadow: none;
    break-after: page;
  }
  .print-design-sheet:last-child {
    break-after: auto;
  }
}
</style>
