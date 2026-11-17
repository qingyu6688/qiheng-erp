import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

function createMarkdown(): InstanceType<typeof MarkdownIt> {
  return new MarkdownIt({ html: false, linkify: false }).disable(['autolink', 'image', 'link'])
}

const markdown = createMarkdown()
const wikiMarkdown = createMarkdown()
wikiMarkdown.inline.ruler.before('link', 'wiki_link', (state, silent) => {
  if (state.src[state.pos] !== '[' || state.src[state.pos + 1] !== '[') return false
  const match = /^\[\[([a-z0-9-]{1,96})]]/.exec(state.src.slice(state.pos))
  const slug = match?.[1]
  if (!match || !slug) return false
  if (!silent) {
    state.push('link_open', 'a', 1).attrSet('href', `/ai/wiki/${slug}`)
    state.push('text', '', 0).content = slug
    state.push('link_close', 'a', -1)
  }
  state.pos += match[0].length
  return true
})

export function renderAiMarkdown(content: string, wikiLinks = false): string {
  const renderer = wikiLinks ? wikiMarkdown : markdown
  return DOMPurify.sanitize(renderer.render(content), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: wikiLinks ? ['img'] : ['a', 'img'],
    ALLOWED_URI_REGEXP: /^\/ai\/wiki\/[a-z0-9-]{1,96}$/,
    ALLOW_DATA_ATTR: false,
  })
}
