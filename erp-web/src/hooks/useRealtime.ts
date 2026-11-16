import { onScopeDispose, watch } from 'vue'
import { useSessionStore } from '../stores/session'

export function useRealtime(changed: () => void) {
  const session = useSessionStore()
  let socket: WebSocket | undefined,
    timer: ReturnType<typeof setTimeout> | undefined,
    attempt = 0,
    disposed = false
  const status = { connected: false }
  function connect(): void {
    if (disposed || !session.user || session.user.mustChangePassword) return
    const current = new WebSocket(
      (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/v1/events',
    )
    socket = current
    current.onopen = (): void => {
      if (socket !== current) return
      status.connected = true
      attempt = 0
      changed()
    }
    current.onmessage = (event: MessageEvent<string>): void => {
      if (socket !== current) return
      try {
        const data: unknown = JSON.parse(event.data)
        if (!data || typeof data !== 'object' || !('type' in data) || typeof data.type !== 'string')
          return
        if (data.type === 'ping') {
          current.send('{"type":"pong"}')
          return
        }
        if (data.type === 'session.kicked') {
          window.dispatchEvent(new Event('erp:session-kicked'))
          return
        }
        if (data.type === 'dict.changed') window.dispatchEvent(new Event('erp:dict-changed'))
        if (data.type.startsWith('message.') || data.type === 'notice.changed' || data.type === 'business.changed') changed()
      } catch {
        current.close(1003, '事件格式无效')
      }
    }
    current.onclose = (): void => {
      if (socket !== current || disposed) return
      status.connected = false
      changed()
      timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5)))
    }
    current.onerror = (): void => {
      status.connected = false
    }
  }
  function stop(): void {
    clearTimeout(timer)
    const current = socket
    socket = undefined
    current?.close()
    status.connected = false
  }
  watch(
    () => session.user?.id,
    () => {
      stop()
      connect()
    },
    { immediate: true },
  )
  // Redis 或代理升级短暂不可用时，页面仍能通过普通接口恢复当前未读数。
  const fallback = setInterval(() => {
    if (!status.connected && session.user) changed()
  }, 30000)
  onScopeDispose(() => {
    disposed = true
    stop()
    clearInterval(fallback)
  })
}
