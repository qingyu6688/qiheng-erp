<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import { REPO } from '@/config/site'
import { demoConfig } from '@/config/demo'
import { useLocale } from '@/hooks/useLocale'
import { spyKey } from '@/hooks/useScrollSpy'
import type { NavEntry, NavKey } from '@/types/content'
import { withBase } from '@/utils/assets'
import NavMegaMenu from './NavMegaMenu.vue'
import NavMenuItem from './NavMenuItem.vue'
import NavSheet from './NavSheet.vue'

const SHEET_BREAKPOINT = 1200
const CLOSE_DELAY_MS = 160

const route = useRoute()
const { locale, chrome } = useLocale()
const demoReady = demoConfig.url !== null
const mascot = withBase('mascot.svg')
const homePath = computed(() => (locale.value === 'en' ? '/en' : '/'))

const navEl = ref<HTMLElement | null>(null)
const scrolled = ref(false)
const progress = ref(0)
const openKey = ref<NavKey | null>(null)
const sheetOpen = ref(false)
const pill = ref({ visible: false, x: 0, y: 0, width: 0, height: 0, instant: true })
let closeTimer: number | undefined
let canHover = false

const pageKey = computed(() => route.meta.navKey)
function isCurrent(key: NavKey): boolean {
  return spyKey.value !== undefined ? spyKey.value === key : pageKey.value === key
}
function hasPanel(entry: NavEntry): boolean {
  return Boolean(entry.groups || entry.items)
}

// 悬停背景块：相对整条导航定位，在一级项之间平滑移动；第一次出现时不做位移动画
function pillTo(el: Element | null): void {
  const nav = navEl.value
  if (!el || !nav) {
    return
  }
  const rect = el.getBoundingClientRect()
  const navRect = nav.getBoundingClientRect()
  pill.value = {
    visible: true,
    instant: !pill.value.visible,
    x: rect.left - navRect.left - nav.clientLeft,
    y: rect.top - navRect.top - nav.clientTop,
    width: rect.width,
    height: rect.height,
  }
  if (pill.value.instant) {
    requestAnimationFrame(() => {
      pill.value.instant = false
    })
  }
}
const pillStyle = computed(() => ({
  width: `${pill.value.width}px`,
  height: `${pill.value.height}px`,
  transform: `translate(${pill.value.x}px, ${pill.value.y}px)`,
  transition: pill.value.instant ? 'none' : undefined,
}))
function hidePill(): void {
  pill.value.visible = false
}

function open(key: NavKey, trigger: Element | null): void {
  window.clearTimeout(closeTimer)
  openKey.value = key
  pillTo(trigger)
}
function closeAll(): void {
  window.clearTimeout(closeTimer)
  openKey.value = null
}
function onItemEnter(entry: NavEntry, event: MouseEvent): void {
  if (!canHover) {
    return
  }
  if (hasPanel(entry)) {
    open(entry.key, (event.currentTarget as HTMLElement).querySelector('.nav-link'))
  } else {
    closeAll()
  }
}
function onItemLeave(entry: NavEntry): void {
  if (!canHover || !hasPanel(entry)) {
    return
  }
  closeTimer = window.setTimeout(() => {
    closeAll()
    hidePill()
  }, CLOSE_DELAY_MS)
}
// 鼠标悬停时面板往往已经展开，鼠标点击只展开不收起；键盘回车、空格（detail 为 0）可切换
function onTriggerClick(entry: NavEntry, event: MouseEvent): void {
  const byMouse = event.detail > 0
  if (openKey.value === entry.key && !(canHover && byMouse)) {
    closeAll()
  } else {
    open(entry.key, event.currentTarget as Element)
  }
}
async function onTriggerKeydown(entry: NavEntry, event: KeyboardEvent): Promise<void> {
  if (event.key !== 'ArrowDown') {
    return
  }
  event.preventDefault()
  open(entry.key, event.currentTarget as Element)
  await nextTick()
  navEl.value?.querySelector<HTMLElement>(`#panel-${entry.key} a`)?.focus()
}
function onItemFocusOut(entry: NavEntry, event: FocusEvent): void {
  const item = event.currentTarget as HTMLElement
  if (openKey.value === entry.key && !item.contains(event.relatedTarget as Node | null)) {
    openKey.value = null
  }
}

function setSheet(value: boolean): void {
  sheetOpen.value = value
}

function onScroll(): void {
  const max = document.documentElement.scrollHeight - window.innerHeight
  scrolled.value = window.scrollY > 8
  progress.value = max > 0 ? Math.min(window.scrollY / max, 1) : 0
}
function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') {
    return
  }
  if (openKey.value) {
    const key = openKey.value
    closeAll()
    navEl.value?.querySelector<HTMLElement>(`[data-key="${key}"]`)?.focus()
  }
  if (sheetOpen.value) {
    setSheet(false)
    navEl.value?.querySelector<HTMLElement>('.menu-btn')?.focus()
  }
}
function onDocumentClick(event: MouseEvent): void {
  if (navEl.value && !navEl.value.contains(event.target as Node)) {
    closeAll()
    hidePill()
  }
}
function onResize(): void {
  if (window.innerWidth >= SHEET_BREAKPOINT) {
    setSheet(false)
  }
}

watch(sheetOpen, (value) => document.documentElement.classList.toggle('nav-lock', value))
watch(
  () => route.fullPath,
  () => {
    closeAll()
    hidePill()
    setSheet(false)
  },
)

onMounted(() => {
  canHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onResize)
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onDocumentClick)
  onScroll()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('click', onDocumentClick)
  document.documentElement.classList.remove('nav-lock')
})
</script>

<template>
  <header ref="navEl" class="nav" :class="{ 'is-scrolled': scrolled, 'is-open': sheetOpen, 'has-panel-open': openKey !== null }">
    <div class="nav-bar">
      <SmartLink class="brand" :href="homePath" :aria-label="chrome.home">
        <img class="logo-mark" :src="mascot" alt="" width="32" height="32" /><span>{{ chrome.brand }}</span><span class="ver">2.0</span>
      </SmartLink>
      <span class="nav-sep" aria-hidden="true"></span>
      <nav class="nav-links" :class="{ 'has-hover': pill.visible }" :aria-label="chrome.mainNav" @mouseleave="openKey === null && hidePill()">
        <span class="nav-hover" aria-hidden="true" :style="pillStyle"></span>
        <div
          v-for="entry in chrome.nav"
          :key="entry.key"
          class="nav-item"
          :class="{ 'has-panel': hasPanel(entry), mega: Boolean(entry.groups), 'is-open': openKey === entry.key }"
          @mouseenter="onItemEnter(entry, $event)"
          @mouseleave="onItemLeave(entry)"
          @focusout="onItemFocusOut(entry, $event)"
        >
          <SmartLink
            v-if="entry.href"
            class="nav-link"
            :class="{ 'is-current': isCurrent(entry.key) }"
            :href="entry.href"
            :data-key="entry.key"
            :aria-current="pageKey === entry.key ? 'page' : undefined"
            @mouseenter="pillTo($event.currentTarget as Element)"
            @focus="pillTo($event.currentTarget as Element)"
          >
            {{ entry.label }}<span v-if="entry.badge" class="nav-badge">{{ entry.badge }}</span>
          </SmartLink>
          <template v-else>
            <button
              class="nav-link"
              :class="{ 'is-current': isCurrent(entry.key) }"
              type="button"
              :data-key="entry.key"
              :aria-expanded="openKey === entry.key"
              :aria-controls="`panel-${entry.key}`"
              @click="onTriggerClick(entry, $event)"
              @keydown="onTriggerKeydown(entry, $event)"
              @mouseenter="pillTo($event.currentTarget as Element)"
              @focus="pillTo($event.currentTarget as Element)"
            >
              {{ entry.label }}<AppIcon name="caret" class="caret" />
            </button>
            <NavMegaMenu v-if="entry.groups" :entry="entry" :mega="chrome.mega" :demo-label="chrome.demo" />
            <div v-else :id="`panel-${entry.key}`" class="nav-panel">
              <NavMenuItem v-for="item in entry.items" :key="item.href" :item="item" />
            </div>
          </template>
        </div>
      </nav>
      <div class="nav-right">
        <SmartLink class="lang" :href="chrome.lang.href" :hreflang="chrome.lang.hreflang" :lang="chrome.lang.hreflang" :title="chrome.lang.title">
          <AppIcon name="globe" />{{ chrome.lang.short }}
        </SmartLink>
        <a class="icon-btn gh" :href="REPO" rel="noopener" :aria-label="chrome.github" :title="chrome.github"><AppIcon name="github" /></a>
        <SmartLink v-if="demoReady" class="btn btn-sm btn-primary" href="/demo">{{ chrome.demo }}<AppIcon name="arrow" class="arr" /></SmartLink>
        <SmartLink v-else class="btn btn-sm is-soon" href="/demo">{{ chrome.demo }} <span class="soon">{{ chrome.soon }}</span></SmartLink>
        <button
          class="icon-btn menu-btn"
          type="button"
          :aria-label="sheetOpen ? chrome.closeMenu : chrome.openMenu"
          :aria-expanded="sheetOpen"
          aria-controls="nav-sheet"
          @click="setSheet(!sheetOpen)"
        >
          <span class="burger" aria-hidden="true"><i></i><i></i></span>
        </button>
      </div>
    </div>
    <NavSheet :chrome="chrome" :page-key="pageKey" @navigate="setSheet(false)" />
    <span class="nav-progress" aria-hidden="true" :style="{ transform: `scaleX(${progress.toFixed(4)})` }"></span>
  </header>
</template>
