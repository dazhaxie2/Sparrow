export interface ResearchCardSummary {
  id: number
  title: string
  brief: string | null
  status: 'DRAFT' | 'RESEARCHING' | 'COMPLETED' | 'FAILED'
  currentStage: string | null
  progress: number
  nodeCount: number
  edgeCount: number
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface ResearchMessage {
  id: number
  role: 'user' | 'assistant'
  agent: string | null
  content: string
  createdAt: string
}

export interface ResearchRun {
  id: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  currentStage: string | null
  progress: number
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface ResearchSource {
  id: number
  sourceRef: string
  title: string
  url: string
  publisher: string | null
  snippet: string | null
}

export interface ResearchGraphNode {
  id: string
  name: string
  type: string
  summary: string
  sourceRefs: string[]
}

export interface ResearchGraphEdge {
  from: string
  to: string
  type: string
  product: string
  sourceRefs: string[]
}

export interface ResearchGraph {
  nodes: ResearchGraphNode[]
  edges: ResearchGraphEdge[]
}

export interface ResearchCardDetail {
  card: ResearchCardSummary
  messages: ResearchMessage[]
  activeRun: ResearchRun | null
  graph: ResearchGraph | null
  reportMarkdown: string | null
  sources: ResearchSource[]
}

export interface ResearchMessageReply {
  userMessage: ResearchMessage
  assistantMessage: ResearchMessage
}

export interface ResearchStartResult {
  runId: number
  remainingQuota: number
}
