export interface DemoConfig {
  /** 演示系统地址，未配置时为 null，全站“在线演示”显示“筹备中” */
  url: string | null
  tenant: string | null
  note: string | null
}

export interface DemoEnv {
  VITE_DEMO_URL?: string
  VITE_DEMO_TENANT?: string
  VITE_DEMO_NOTE?: string
}

/**
 * 读取构建时注入的演示配置。地址格式不对或不是 https 时直接抛错，让构建失败，
 * 避免把错误地址发布到官网上。
 */
export function parseDemoConfig(env: DemoEnv): DemoConfig {
  const raw = (env.VITE_DEMO_URL ?? '').trim()
  let url: string | null = null
  if (raw) {
    const parsed = new URL(raw)
    if (parsed.protocol !== 'https:') {
      throw new Error('VITE_DEMO_URL 必须使用 https')
    }
    url = parsed.toString()
  }
  return {
    url,
    tenant: env.VITE_DEMO_TENANT?.trim() || null,
    note: env.VITE_DEMO_NOTE?.trim() || null,
  }
}

/** 跳转时带上来源参数，演示系统可据此统计来访，官网本身不做统计 */
export function demoTarget(url: string): string {
  const target = new URL(url)
  target.searchParams.set('from', 'website')
  return target.toString()
}

export const demoConfig: DemoConfig = parseDemoConfig(import.meta.env as DemoEnv)
