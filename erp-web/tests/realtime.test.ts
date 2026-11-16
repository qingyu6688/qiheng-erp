import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { effectScope, nextTick, reactive } from 'vue'
import type { EffectScope } from 'vue'
import { useRealtime } from '../src/hooks/useRealtime'

const session = reactive<{ user: { id: string; mustChangePassword: boolean } | null }>({
  user: { id: '1', mustChangePassword: false },
})
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
class Socket {
  static instances: Socket[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  send = vi.fn()
  close = vi.fn(() => this.onclose?.())
  constructor(public url: string) {
    Socket.instances.push(this)
  }
}
let scope: EffectScope
beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('WebSocket', Socket)
  Socket.instances = []
  session.user = { id: '1', mustChangePassword: false }
  scope = effectScope()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})
it('连接恢复后重新拉取消息，心跳只回复pong，字典变更触发刷新事件', () => {
  const changed = vi.fn(),
    dictionary = vi.fn()
  window.addEventListener('erp:dict-changed', dictionary)
  scope.run(() => useRealtime(changed))
  const socket = Socket.instances[0]!
  expect(socket.url).toMatch(/^ws:\/\//)
  socket.onopen?.()
  expect(changed).toHaveBeenCalledTimes(1)
  socket.onmessage?.({ data: '{"type":"ping"}' })
  expect(socket.send).toHaveBeenCalledWith('{"type":"pong"}')
  socket.onmessage?.({ data: '{"type":"dict.changed"}' })
  expect(dictionary).toHaveBeenCalledTimes(1)
  socket.onmessage?.({ data: '{"type":"message.changed"}' })
  expect(changed).toHaveBeenCalledTimes(2)
  socket.onmessage?.({ data: '{"type":"business.changed"}' }); expect(changed).toHaveBeenCalledTimes(3)
  const kicked = vi.fn()
  window.addEventListener('erp:session-kicked', kicked)
  socket.onmessage?.({ data: '{"type":"session.kicked"}' })
  expect(kicked).toHaveBeenCalledTimes(1)
  window.removeEventListener('erp:session-kicked', kicked)
  window.removeEventListener('erp:dict-changed', dictionary)
})
it('断开后按退避重连，卸载和退出都清理连接与计时器', async () => {
  scope.run(() => useRealtime(vi.fn()))
  Socket.instances[0]!.onclose?.()
  await vi.advanceTimersByTimeAsync(999)
  expect(Socket.instances).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(Socket.instances).toHaveLength(2)
  session.user = null
  await nextTick()
  expect(Socket.instances[1]!.close).toHaveBeenCalled()
  scope.stop()
  expect(vi.getTimerCount()).toBe(0)
})
it('账户变更不会复用旧连接，旧连接的消息不能刷新新账户页面', async () => {
  const changed = vi.fn()
  scope.run(() => useRealtime(changed))
  const old = Socket.instances[0]!
  session.user = { id: '2', mustChangePassword: false }
  await nextTick()
  expect(Socket.instances).toHaveLength(2)
  old.onmessage?.({ data: '{"type":"message.changed"}' })
  expect(changed).not.toHaveBeenCalled()
  Socket.instances[1]!.onopen?.()
  expect(changed).toHaveBeenCalledTimes(1)
})
