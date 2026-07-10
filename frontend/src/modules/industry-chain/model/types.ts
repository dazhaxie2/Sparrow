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

export interface AttachmentRequest {
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
  reportIr: DocumentIr | null
  reportMarkdown: string | null
  sources: ResearchSource[]
  attachments: ResearchSource[]
}

// ===== 论坛协作事件(对标 BettaFish forum_message) =====
export interface ForumEventView {
  id: number
  source: 'INDUSTRY' | 'QUERY' | 'INSIGHT' | 'HOST' | 'SYSTEM' | string
  sourceText: string
  content: string
  createdAt: string
}

// SSE 推送的论坛事件载荷(后端 ChainForumBus 发出)
export interface ForumSsePayload {
  runId: number
  event: { cardId: number; runId: number; source: string; content: string; createdAt: string }
}

// ===== Document IR 富报告类型(对标 BettaFish ReportEngine IR) =====
export interface DocumentIr {
  metadata: {
    title: string | null
    subtitle: string | null
    generatedAt: string | null
    query: string | null
    toc: TocEntry[] | null
  } | null
  chapters: Chapter[]
}

export interface TocEntry {
  level: number
  display: string
  anchor: string
}

export interface Chapter {
  chapterId: string
  title: string
  anchor: string
  order: number
  summary: string | null
  blocks: Block[]
}

export interface InlineRun {
  text: string
  marks?: InlineMark[]
}

export interface InlineMark {
  type: 'bold' | 'italic' | 'code' | 'link' | 'source' | 'color' | 'highlight' | string
  value?: string | null
  href?: string | null
  title?: string | null
}

export type Block =
  | { type: 'heading'; level: number; text: string; anchor: string }
  | { type: 'paragraph'; inlines: InlineRun[] }
  | { type: 'list'; items: Block[][] }
  | { type: 'table'; rows: { header: InlineRun[][][] | null; body: InlineRun[][][] | null; caption: string | null } }
  | { type: 'swotTable'; swot: SwotTable }
  | { type: 'pestTable'; pest: PestTable }
  | { type: 'blockquote'; inlines: InlineRun[] }
  | { type: 'callout'; tone: string; text: string; items: Block[][] }
  | { type: 'kpiGrid'; kpi: { items: KpiItem[]; cols: number | null } }
  | { type: 'widget'; widget: Widget }
  | { type: 'hr' }

export interface SwotTable {
  strengths: SwotItem[]
  weaknesses: SwotItem[]
  opportunities: SwotItem[]
  threats: SwotItem[]
}

export interface SwotItem {
  title: string
  detail: string | null
  impact: string | null
}

export interface PestTable {
  political: PestItem[]
  economic: PestItem[]
  social: PestItem[]
  technological: PestItem[]
}

export interface PestItem {
  title: string
  detail: string | null
  trend: string | null
  source: string | null
}

export interface KpiItem {
  label: string
  value: string
  unit: string | null
  delta: string | null
  deltaTone: string | null
}

export interface Widget {
  widgetId: string
  widgetType: string
  title: string | null
  data: unknown
}

export interface ResearchMessageReply {
  userMessage: ResearchMessage
  assistantMessage: ResearchMessage
}

export interface ResearchStartResult {
  runId: number
  remainingQuota: number
}

export interface ResearchResumeResult {
  runId: number
  currentStage: string | null
  progress: number
}
