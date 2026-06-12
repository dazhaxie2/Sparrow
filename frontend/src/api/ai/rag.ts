import { get } from '../request'

export interface KnowledgeBase {
  id: string
  name: string
  documentCount: number
}

export function fetchKnowledgeBases() {
  return get<KnowledgeBase[]>('/api/ai/knowledge-bases')
}
