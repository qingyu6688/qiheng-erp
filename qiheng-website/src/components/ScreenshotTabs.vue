<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import BrowserFrame from '@/components/BrowserFrame.vue'
import ShotImage from '@/components/ShotImage.vue'
import { canObserve, prefersReducedMotion } from '@/hooks/motion'
import { shotSrc, type ShotKey } from '@/utils/assets'

interface Shot {
  key: ShotKey
  label: string
  url: string
  caption: string
}

/**
 * 截图页签：分段控件切换截图，白色滑块跟随选中项。
 * 自动轮播时滑块底部的进度条走完就切到下一张；悬停、聚焦、离开视口或减少动态效果时停下。
 */
const props = withDefaults(defineProps<{ shots: Shot[]; label: string; autoplayMs?: number }>(), { autoplayMs: 0 })

const index = ref(0)
const current = computed(() => props.shots[index.value] ?? props.shots[0])
const segEl = ref<HTMLElement | null>(null)
const frameEl = ref<HTMLElement | null>(null)
const tabEls = ref<HTMLButtonElement[]>([])
const thumb = ref({ x: 0, width: 0, ready: false })
const autoplay = ref(false)
const playing = ref(false)
const hovered = ref(false)
const focused = ref(false)
const inView = ref(false)
const held = computed(() => hovered.value || focused.value || !inView.value)
let observer: IntersectionObserver | null = null

function moveThumb(): void {
  const tab = tabEls.value[index.value]
  if (tab) {
    thumb.value = { x: tab.offsetLeft, width: tab.offsetWidth, ready: true }
  }
}

// 重新挂上 is-playing 让进度条从头开始
async function restartProgress(): Promise<void> {
  if (!autoplay.value) {
    return
  }
  playing.value = false
  await nextTick()
  void segEl.value?.offsetWidth
  playing.value = inView.value
}

function select(next: number, focus = false): void {
  index.value = (next + props.shots.length) % props.shots.length
  moveThumb()
  if (focus) {
    tabEls.value[index.value]?.focus()
  }
  void restartProgress()
}

function onKeydown(event: KeyboardEvent): void {
  const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (step) {
    select(index.value + step, true)
  }
}

onMounted(() => {
  moveThumb()
  window.addEventListener('resize', moveThumb)
  // 预先加载其余截图，切换时不闪白
  props.shots.forEach((shot) => {
    const img = new Image()
    img.src = shotSrc(shot.key)
  })
  autoplay.value = props.autoplayMs > 0 && !prefersReducedMotion() && canObserve()
  if (autoplay.value && frameEl.value) {
    observer = new IntersectionObserver(
      ([entry]) => {
        inView.value = Boolean(entry?.isIntersecting)
        if (inView.value && !playing.value) {
          void restartProgress()
        }
      },
      { threshold: 0.35 },
    )
    observer.observe(frameEl.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', moveThumb)
  observer?.disconnect()
})
</script>

<template>
  <div @mouseenter="hovered = true" @mouseleave="hovered = false">
    <div class="tabs-row" data-reveal>
      <div
        ref="segEl"
        class="seg"
        :class="{ 'has-thumb': thumb.ready, 'is-autoplay': autoplay, 'is-playing': playing, 'is-held': held }"
        role="tablist"
        :aria-label="label"
        :style="{ '--autoplay': `${autoplayMs}ms` }"
        @focusin="focused = true"
        @focusout="focused = false"
      >
        <span
          class="seg-thumb"
          aria-hidden="true"
          :style="{ width: `${thumb.width}px`, transform: `translateX(${thumb.x}px)` }"
          @animationend="select(index + 1)"
        ></span>
        <button
          v-for="(shot, i) in shots"
          :key="shot.key"
          ref="tabEls"
          class="tab"
          role="tab"
          type="button"
          :aria-selected="i === index"
          :tabindex="i === index ? 0 : -1"
          @click="select(i)"
          @keydown="onKeydown"
        >
          {{ shot.label }}
        </button>
      </div>
    </div>
    <div ref="frameEl" class="shot-wrap" data-reveal="scale">
      <BrowserFrame :url="current.url">
        <Transition name="shot-fade" mode="out-in">
          <ShotImage :key="current.key" :shot="current.key" :alt="current.caption" />
        </Transition>
      </BrowserFrame>
    </div>
    <p class="caption" aria-live="polite">{{ current.caption }}</p>
  </div>
</template>
