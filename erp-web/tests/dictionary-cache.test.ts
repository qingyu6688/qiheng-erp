import { beforeEach, expect, it } from 'vitest'
import {
  cachedDictionary,
  cacheDictionary,
  clearDictionaryCache,
} from '../src/utils/dictionary-cache'

beforeEach(() => sessionStorage.clear())
const dictionary = {
  version: '4',
  items: [
    { id: '1', value: 'expo', label: '展会', color: 'primary', enabled: true },
    { id: '2', value: 'old', label: '历史来源', color: 'neutral', enabled: false },
  ],
}
it('按登录用户隔离缓存，保留停用项文字与版本', () => {
  cacheDictionary('1', 'customer_source', dictionary)
  expect(cachedDictionary('1', 'customer_source')).toEqual(dictionary)
  expect(cachedDictionary('2', 'customer_source')).toBeUndefined()
})
it('损坏或结构不符的缓存会移除，交给服务端重新加载', () => {
  for (const value of [
    '{',
    '{"version":3,"items":[]}',
    '{"version":"1","items":[{"enabled":"false"}]}',
  ]) {
    sessionStorage.setItem('erp.dict.1.customer_source', value)
    expect(cachedDictionary('1', 'customer_source')).toBeUndefined()
    expect(sessionStorage.getItem('erp.dict.1.customer_source')).toBeNull()
  }
})
it('退出时清理字典缓存，保留无关的页面配置', () => {
  sessionStorage.setItem('erp.company', '3')
  cacheDictionary('1', 'customer_source', dictionary)
  cacheDictionary('2', 'contact_type', dictionary)
  clearDictionaryCache()
  expect(sessionStorage.length).toBe(1)
  expect(sessionStorage.getItem('erp.company')).toBe('3')
})
