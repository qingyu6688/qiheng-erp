import type { InjectionKey } from 'vue'
import type { AiDocumentContext } from '../types/ai'

export const openAiDocument: InjectionKey<(context: AiDocumentContext) => void> =
  Symbol('openAiDocument')
