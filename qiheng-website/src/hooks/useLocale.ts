import { computed, type ComputedRef } from 'vue'
import { useRoute } from 'vue-router'
import { CHROME } from '@/content/chrome'
import type { Locale, SiteChrome } from '@/types/content'

export function useLocale(): { locale: ComputedRef<Locale>; chrome: ComputedRef<SiteChrome> } {
  const route = useRoute()
  const locale = computed<Locale>(() => (route.meta.locale === 'en' ? 'en' : 'zh'))
  const chrome = computed(() => CHROME[locale.value])
  return { locale, chrome }
}
