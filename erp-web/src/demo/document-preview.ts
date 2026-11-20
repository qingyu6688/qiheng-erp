import { computed, shallowRef } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'

const previewStack = shallowRef<RouteLocationNormalized[]>([])
export const previewRoute = computed(() => previewStack.value.at(-1))
export const canReturnPreview = computed(() => previewStack.value.length > 1)

export function openDocumentPreview(route: RouteLocationNormalized): void {
  if (previewRoute.value?.path === route.path) return
  previewStack.value = [...previewStack.value, route]
}

export function closeDocumentPreview(): void {
  previewStack.value = []
}

export function returnDocumentPreview(): void {
  if (canReturnPreview.value) previewStack.value = previewStack.value.slice(0, -1)
}
