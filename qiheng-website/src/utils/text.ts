export interface TextSegment {
  text: string
  code: boolean
}

/** 把用反引号标出的命令、变量名拆出来，渲染成行内代码 */
export function splitInlineCode(text: string): TextSegment[] {
  return text
    .split('`')
    .map((part, index) => ({ text: part, code: index % 2 === 1 }))
    .filter((segment) => segment.text.length > 0)
}

/** 把文案里的 {amount} 占位拆成前后两段，金额单独加粗 */
export function splitPlaceholder(text: string, key: string): [string, string] {
  const marker = `{${key}}`
  const index = text.indexOf(marker)
  if (index < 0) {
    return [text, '']
  }
  return [text.slice(0, index), text.slice(index + marker.length)]
}
