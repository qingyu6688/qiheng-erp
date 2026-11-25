import { demoAiAnswers, demoAiDocuments, demoAiEvidence, demoAiLibraries, demoAiSegments, demoAiWiki } from '../demo/ai-data'

export function readAiDemo(path: string, params: Record<string, unknown>): unknown {
  const query = String(params.q ?? '').trim().toLowerCase()
  if (path === '/wiki') return demoAiWiki.filter((page) => !query || [page.title, page.summary, page.content].join(' ').toLowerCase().includes(query))
  if (path.startsWith('/wiki/')) return demoAiWiki.find((page) => page.slug === path.slice('/wiki/'.length))
  if (path === '/admin/libraries') return demoAiLibraries
  if (path === '/admin/documents') return demoAiDocuments.filter((document) => !params.libraryId || document.libraryId === params.libraryId)
  const document = /^\/admin\/documents\/([0-9]+)(\/segments)?$/.exec(path)
  if (document && demoAiDocuments.some((item) => item.id === document[1])) return document[2]
    ? demoAiSegments.filter((segment) => segment.documentId === document[1])
    : demoAiDocuments.find((item) => item.id === document[1])
  if (path.startsWith('/citations/')) return demoAiEvidence.find((source) => source.segmentId === path.slice('/citations/'.length))
  if (path === '/demo/answers') return demoAiAnswers.filter((answer) => (!query || [answer.question, answer.summary, answer.category].join(' ').toLowerCase().includes(query)) && (!params.category || answer.category === params.category))
  if (path.startsWith('/demo/answers/')) return demoAiAnswers.find((answer) => answer.id === path.slice('/demo/answers/'.length))
  return undefined
}
