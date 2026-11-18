<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, useId, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    storageKey?: string
    initialWidth?: number
    minWidth?: number
    maxWidth?: number
    minSecondaryWidth?: number
    label?: string
  }>(),
  {
    initialWidth: 280,
    minWidth: 220,
    maxWidth: 480,
    minSecondaryWidth: 320,
    label: '调整左右分栏宽度',
  },
)
const root = ref<HTMLElement>()
const divider = ref<HTMLElement>()
const width = ref(props.initialWidth)
const containerWidth = ref(0)
const pointer = ref<number>()
const primaryId = useId()
const secondaryId = useId()
let observer: ResizeObserver | undefined
const maximum = computed(() =>
  Math.max(
    0,
    Math.min(
      props.maxWidth,
      containerWidth.value ? containerWidth.value - props.minSecondaryWidth - 8 : props.maxWidth,
    ),
  ),
)
const minimum = computed(() => Math.min(props.minWidth, maximum.value))
const currentWidth = computed(() =>
  Math.round(Math.min(maximum.value, Math.max(minimum.value, width.value))),
)

function save(): void {
  if (!props.storageKey) return
  try {
    localStorage.setItem(props.storageKey, String(currentWidth.value))
  } catch {
    // 存储受限时保留当次页面的分栏宽度。
  }
}

function endDrag(): void {
  if (pointer.value !== undefined && divider.value?.hasPointerCapture(pointer.value)) {
    divider.value.releasePointerCapture(pointer.value)
  }
  pointer.value = undefined
}

function drag(event: PointerEvent): void {
  if (pointer.value !== event.pointerId || !root.value) return
  width.value = Math.min(
    maximum.value,
    Math.max(minimum.value, event.clientX - root.value.getBoundingClientRect().left),
  )
}

function startDrag(event: PointerEvent): void {
  if (event.button !== 0) return
  event.preventDefault()
  divider.value?.focus()
  pointer.value = event.pointerId
  divider.value?.setPointerCapture(event.pointerId)
  drag(event)
}

function finishDrag(event: PointerEvent): void {
  if (pointer.value !== event.pointerId) return
  endDrag()
  save()
}

function resizeByKeyboard(event: KeyboardEvent): void {
  const step = event.shiftKey ? 64 : 16
  let next = currentWidth.value
  if (event.key === 'ArrowLeft') next -= step
  else if (event.key === 'ArrowRight') next += step
  else if (event.key === 'Home') next = minimum.value
  else if (event.key === 'End') next = maximum.value
  else return
  event.preventDefault()
  width.value = Math.min(maximum.value, Math.max(minimum.value, next))
  save()
}

watch(
  () => props.storageKey,
  (key) => {
    endDrag()
    width.value = props.initialWidth
    if (!key) return
    try {
      const stored = localStorage.getItem(key)
      const value = stored === null ? NaN : Number(stored)
      if (Number.isFinite(value) && value > 0) width.value = value
    } catch {
      // 配置读取失败时使用默认宽度。
    }
  },
  { immediate: true, flush: 'sync' },
)

onMounted(() => {
  containerWidth.value = root.value?.getBoundingClientRect().width ?? 0
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver((entries) => {
    containerWidth.value = entries[0]?.contentRect.width ?? 0
  })
  if (root.value) observer.observe(root.value)
})
onScopeDispose(() => {
  endDrag()
  observer?.disconnect()
})
</script>

<template>
  <div ref="root" class="split-pane" :style="{ '--split-primary-width': currentWidth + 'px' }">
    <div :id="primaryId" class="split-primary"><slot name="primary" /></div>
    <div
      ref="divider"
      class="split-divider"
      :class="{ 'is-dragging': pointer !== undefined }"
      role="separator"
      tabindex="0"
      aria-orientation="vertical"
      :aria-label="label"
      :aria-controls="primaryId + ' ' + secondaryId"
      :aria-valuemin="minimum"
      :aria-valuemax="maximum"
      :aria-valuenow="currentWidth"
      :aria-valuetext="'左侧宽度 ' + currentWidth + ' 像素'"
      @pointerdown="startDrag"
      @pointermove="drag"
      @pointerup="finishDrag"
      @pointercancel="finishDrag"
      @lostpointercapture="endDrag"
      @keydown="resizeByKeyboard"
    />
    <div :id="secondaryId" class="split-secondary"><slot name="secondary" /></div>
  </div>
</template>

<style scoped>
.split-pane {
  display: grid;
  grid-template-columns: var(--split-primary-width) 8px minmax(0, 1fr);
  align-items: start;
}
.split-primary,
.split-secondary {
  min-width: 0;
}
.split-divider {
  align-self: stretch;
  position: relative;
  min-height: 120px;
  cursor: col-resize;
  touch-action: none;
}
.split-divider::before {
  content: '';
  position: absolute;
  inset: 0 3px;
  border-radius: var(--erp-radius-sm);
  background: var(--erp-border-1);
}
.split-divider:hover::before,
.split-divider:focus-visible::before,
.split-divider.is-dragging::before {
  background: var(--erp-primary-6);
}
@media (max-width: 767px) {
  .split-pane {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
  .split-divider {
    display: none;
  }
}
</style>
