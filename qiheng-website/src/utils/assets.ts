/** public/ 下的静态资源地址，自动带上部署时的 base（GitHub Pages 为 /qiheng-erp/） */
export function withBase(path: string): string {
  return import.meta.env.BASE_URL + path.replace(/^\//, '')
}

export type ShotKey = 'workbench' | 'orders' | 'detail' | 'quotes' | 'revaluation' | 'pda'

/** 界面截图的 1 倍图地址 */
export function shotSrc(key: ShotKey): string {
  return withBase(`images/shots/${key}.webp`)
}

/** 截图的 srcset：1 倍、2 倍两档，宽度分别为 1440 和 2880（PDA 为 344 和 688） */
export function shotSrcset(key: ShotKey): string {
  const width = key === 'pda' ? 344 : 1440
  return `${shotSrc(key)} ${width}w, ${withBase(`images/shots/${key}@2x.webp`)} ${width * 2}w`
}
