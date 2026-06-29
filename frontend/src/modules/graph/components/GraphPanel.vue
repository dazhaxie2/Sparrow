<template>
  <aside class="panel" :class="{ floating, collapsed: floating && drawerCollapsed, expanded: sheetExpanded, 'sheet-collapsed': !floating && !sheetExpanded }">
    <div class="panel-header">
      <div>
        <BookOpen :size="15" />
        <strong>节点详情</strong>
      </div>
      <button
        v-if="floating"
        class="drawer-toggle"
        type="button"
        :title="drawerCollapsed ? '展开详情' : '收起详情'"
        @click="drawerCollapsed = !drawerCollapsed"
      >
        <PanelRightOpen v-if="drawerCollapsed" :size="15" />
        <PanelRightClose v-else :size="15" />
      </button>
      <button
        v-else
        class="sheet-toggle"
        type="button"
        :title="sheetExpanded ? '收起详情' : '展开详情'"
        @click="sheetExpanded = !sheetExpanded"
      >
        <ChevronDown v-if="sheetExpanded" :size="15" />
        <ChevronUp v-else :size="15" />
      </button>
      <span class="panel-state">{{ statusLabel }}</span>
    </div>

    <div v-show="!drawerCollapsed" ref="bodyRef" class="panel-body">
      <template v-if="!detail && !preview && !loading && !error">
        <section class="empty-state">
          <p class="eyebrow">NODE INSPECTOR</p>
          <h2>尚未选择节点</h2>
          <p>节点摘要、关联技术与学习状态将在此处显示。</p>
        </section>
      </template>

      <template v-else>
        <section class="node-section" :style="{ '--current-node-color': accentColor }">
          <div class="node-meta">
            <span class="era-badge">{{ currentEra }}</span>
            <span v-if="currentCategory" class="category-badge">{{ currentCategory }}</span>
            <span>{{ currentYear }}</span>
            <span v-if="currentPremium" class="premium-badge"><LockKeyhole :size="12" /> 深度内容</span>
          </div>
          <h2 class="node-title">{{ currentName }}</h2>
          <p class="node-summary">{{ currentSummary }}</p>
          <div class="node-actions">
            <button type="button" :class="{ active: progress === 'want' }" @click="$emit('setProgress', 'want')">
              <BookmarkPlus :size="14" />
              想学
            </button>
            <button type="button" :class="{ active: progress === 'read' }" @click="$emit('setProgress', 'read')">
              <CircleCheck :size="14" />
              已读
            </button>
            <button type="button" :class="{ active: progress === 'mastered' }" @click="$emit('setProgress', 'mastered')">
              <Target :size="14" />
              掌握
            </button>
            <button type="button" class="compare-btn" @click="$emit('addCompare')">
              <GitCompare :size="14" />
              加入对比
            </button>
          </div>
        </section>

        <section v-if="loading" class="loading-box" aria-live="polite">
          <LoaderCircle class="spin" :size="18" />
          <strong>正在加载节点详情</strong>
          <div class="skeleton-line wide"></div>
          <div class="skeleton-line"></div>
          <div class="skeleton-line short"></div>
        </section>

        <section v-if="error" class="error-box">
          <AlertTriangle :size="18" />
          <div>
            <strong>详情加载失败</strong>
            <p>{{ error }}</p>
          </div>
          <button type="button" @click="$emit('retry')">重试</button>
        </section>

        <template v-if="detail && !loading && !error">
          <section v-if="detail.locked" class="locked-box">
            <div class="section-title">
              <LockKeyhole :size="15" />
              <span>会员深度解读</span>
            </div>
            <p class="locked-preview">{{ detail.summary }}</p>
            <div class="fade-preview">
              <span></span>
              <span></span>
            </div>
            <button class="btn accent" type="button" @click="$emit('openMember')">解锁完整内容</button>
          </section>

          <section v-else-if="detail.detail" class="detail-copy">
            <div class="section-title">
              <FileText :size="15" />
              <span>深度内容</span>
            </div>
            <p>{{ detail.detail }}</p>
          </section>

          <section v-if="sources.length" class="source-block">
            <div class="section-title">
              <ExternalLink :size="15" />
              <span>资料来源</span>
              <small>{{ sources.length }}</small>
            </div>
            <div class="source-list">
              <a
                v-for="source in sources"
                :key="`${source.title}-${source.url}`"
                :href="source.url"
                target="_blank"
                rel="noreferrer"
              >
                <strong>{{ source.title || currentName }}</strong>
                <span>{{ source.updatedAt ? `更新于 ${source.updatedAt} · ${source.source}` : source.source }}</span>
              </a>
            </div>
          </section>

          <section v-if="recommendations.length" class="relation-block recommend-block">
            <div class="section-title">
              <Lightbulb :size="15" />
              <span>推荐学习</span>
              <small>{{ recommendations.length }}</small>
            </div>
            <div class="rel-chips">
              <button
                v-for="item in recommendations"
                :key="item.id"
                type="button"
                :class="{ learned: item.state === 'mastered' || item.state === 'read' }"
                @click="$emit('select', item.id)"
              >
                {{ item.name }}
                <span class="rel-tag" :class="item.direction">{{ item.direction === 'pre' ? '前置' : '后续' }}</span>
                <span v-if="item.state === 'mastered'" class="rel-tag learned-tag">已掌握</span>
                <span v-else-if="item.state === 'read'" class="rel-tag learned-tag">已读</span>
              </button>
            </div>
          </section>

          <section v-if="applications.length" class="relation-block application-block">
            <div class="section-title">
              <Boxes :size="15" />
              <span>应用与产业链</span>
              <small>{{ applications.length }}</small>
            </div>
            <div class="rel-chips">
              <button
                v-for="item in applications"
                :key="item.id"
                type="button"
                @click="$emit('select', item.id)"
              >
                {{ item.name }}
              </button>
            </div>
          </section>

          <section v-if="applicationsLoading" class="loading-box application-loading">
            <LoaderCircle class="spin" :size="16" />
            <strong>正在分析应用与产业链</strong>
            <div class="skeleton-line wide"></div>
          </section>
        </template>
      </template>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  AlertTriangle,
  BookOpen,
  Boxes,
  FileText,
  GitCompare,
  Lightbulb,
  LoaderCircle,
  LockKeyhole,
  BookmarkPlus,
  ChevronDown,
  ChevronUp,
  CircleCheck,
  ExternalLink,
  PanelRightClose,
  PanelRightOpen,
  Target,
} from '@lucide/vue'
import type { NodeBrief, NodeDetail } from '../types'

type ProgressState = 'want' | 'read' | 'mastered' | null

const props = defineProps<{
  detail: NodeDetail | null
  preview: NodeBrief | null
  loading: boolean
  error: string
  progress: ProgressState
  /** 全局学习进度(节点 id → 状态),供推荐算法对已学节点降权。 */
  progressMap?: Record<number, Exclude<ProgressState, null>>
  accentColor: string
  applications: NodeBrief[]
  applicationsLoading: boolean
  floating?: boolean
}>()

defineEmits<{
  select: [id: number]
  retry: []
  openMember: []
  setProgress: [state: Exclude<ProgressState, null>]
  addCompare: []
}>()

const bodyRef = ref<HTMLElement | null>(null)
const drawerCollapsed = ref(false)
const sheetExpanded = ref(false)
const current = computed(() => props.detail ?? props.preview)
const currentName = computed(() => current.value?.name ?? '加载中')
const currentEra = computed(() => current.value?.era ?? '未知时代')
const currentYear = computed(() => current.value?.yearLabel ?? '未知年代')
const currentSummary = computed(() => current.value?.summary ?? '正在整理该节点的摘要。')
const currentCategory = computed(() => current.value?.category ?? '')
const currentPremium = computed(() => Boolean(current.value?.premium))
const sources = computed(() => props.detail?.sources ?? [])

type RecDirection = 'pre' | 'post'

const RECOMMEND_LIMIT = 10

/**
 * 推荐学习评分:把「直接前置」(pre,先修基础) 与「后续解锁」(post,进阶方向) 统一为一个
 * 可排序的推荐列表。底层有向数据模型(prerequisites/unlocks)保持不变,这里只在展示层做
 * 加权排序。评分维度:
 *  1) 方向基线:未掌握的前置是理解当前节点的必要基础,基线权重高于后续进阶。
 *  2) 学习进度:已掌握/已读的节点价值下降(下沉),「想学」略微上浮。
 *  3) 节点重要度:相对当前候选集归一化,越核心越靠前(规避未知量纲)。
 *  4) 同类加成:与当前节点同 category 的节点学习路径更连贯,小幅加权。
 *  5) 会员内容:深度付费内容轻微下沉,优先推荐可直接学习的节点。
 */
function scoreCandidate(node: NodeBrief, direction: RecDirection, maxImportance: number) {
  let score = direction === 'pre' ? 1 : 0.72
  const state = props.progressMap?.[node.id]
  if (state === 'mastered') score *= 0.18
  else if (state === 'read') score *= 0.55
  else if (state === 'want') score *= 1.12
  const importance = Math.max(0, node.importance ?? 0)
  score *= 1 + 0.5 * (maxImportance > 0 ? importance / maxImportance : 0)
  if (node.category && current.value?.category && node.category === current.value.category) score *= 1.15
  if (node.premium) score *= 0.9
  return score
}

const recommendations = computed(() => {
  const detail = props.detail
  if (!detail) return []
  // 去重:同一节点若既是前置又是后续,保留「前置」(更基础)。
  const byId = new Map<number, { node: NodeBrief; direction: RecDirection }>()
  for (const node of detail.prerequisites) byId.set(node.id, { node, direction: 'pre' })
  for (const node of detail.unlocks) if (!byId.has(node.id)) byId.set(node.id, { node, direction: 'post' })

  const items = [...byId.values()]
  const maxImportance = items.reduce((max, item) => Math.max(max, item.node.importance ?? 0), 0)
  return items
    .map(({ node, direction }) => ({
      ...node,
      direction,
      state: props.progressMap?.[node.id] ?? null,
      score: scoreCandidate(node, direction, maxImportance),
    }))
    .sort((a, b) => b.score - a.score)
    .slice(0, RECOMMEND_LIMIT)
})

const allSources = computed(() => {
  const baikeSource = {
    title: currentName.value,
    url: `https://baike.baidu.com/item/${encodeURIComponent(currentName.value || '')}`,
    source: '百度百科',
    updatedAt: undefined
  }
  const originalSources = sources.value.map(src => ({
    ...src,
    source: (src as any).source || '维基百科'
  }))
  return [baikeSource, ...originalSources]
})
const statusLabel = computed(() => {
  if (props.loading) return 'LOADING'
  if (props.error) return 'RETRY'
  return current.value ? 'SELECTED' : 'WAITING'
})

watch(() => current.value?.id, async () => {
  await nextTick()
  bodyRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
})

watch(() => props.floating, value => {
  drawerCollapsed.value = Boolean(value)
})
</script>

<style scoped>
.panel {
  width: 326px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.panel.floating {
  position: fixed;
  top: 72px;
  right: 18px;
  bottom: auto;
  z-index: 80;
  width: min(326px, calc(100vw - 36px));
  max-height: calc(100vh - 92px);
  border: 1px solid rgba(20, 24, 29, 0.1);
  box-shadow: 0 18px 50px rgba(20, 24, 29, 0.14);
  backdrop-filter: blur(18px);
}

.panel.floating.collapsed {
  top: 104px;
  right: 18px;
  bottom: auto;
  width: 38px;
  height: 38px;
  border-radius: 999px;
  overflow: visible;
}

.panel.floating.collapsed .panel-header {
  min-height: 0;
  height: 38px;
  display: grid;
  place-items: center;
  padding: 0;
  border-bottom: 0;
  background: var(--panel);
}

.panel.floating.collapsed .panel-header div,
.panel.floating.collapsed .panel-state {
  display: none;
}

.panel.floating.collapsed .drawer-toggle {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 999px;
  background: var(--panel);
  box-shadow: var(--shadow-sm);
}

.panel-header {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 13px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.97);
}

.panel-header div {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 0.08em;
}

.panel-header svg {
  color: var(--accent);
}

.drawer-toggle,
.sheet-toggle {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  cursor: pointer;
}

.drawer-toggle:hover,
.sheet-toggle:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.sheet-toggle {
  display: none;
}

.panel-state {
  border: 0;
  border-radius: 999px;
  background: var(--accent);
  padding: 4px 8px;
  color: #fff;
  font-size: 9px;
  letter-spacing: 0.08em;
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.empty-state,
.node-section,
.relation-block,
.detail-copy,
.locked-box,
.loading-box,
.error-box,
.source-block {
  margin: 15px;
}

.eyebrow {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.empty-state h2,
.node-title {
  margin-top: 8px;
  font-size: 20px;
  line-height: 1.2;
  letter-spacing: 0;
}

.empty-state p {
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.85;
}

.node-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.node-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 5px 8px;
  color: var(--ink-2);
  font-size: 11px;
}

.premium-badge {
  border-color: rgba(255, 87, 34, 0.35) !important;
  color: var(--accent) !important;
}

.category-badge {
  border-color: color-mix(in srgb, var(--current-node-color, var(--accent)) 40%, white) !important;
  background: color-mix(in srgb, var(--current-node-color, var(--accent)) 10%, white) !important;
  color: var(--current-node-color, var(--accent)) !important;
  font-weight: 700;
}

.era-badge {
  color: var(--muted) !important;
}

.node-summary {
  margin-top: 12px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.9;
}

.node-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.node-actions button {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 9px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.node-actions button:hover,
.node-actions button.active {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.06);
  color: var(--accent);
}

.node-actions .compare-btn {
  margin-left: auto;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.section-title svg {
  color: var(--accent);
}

.section-title small {
  margin-left: auto;
  color: var(--muted);
}

.loading-box,
.error-box {
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 14px;
}

.loading-box {
  display: grid;
  gap: 9px;
  color: var(--ink-2);
}

.loading-box svg {
  color: var(--accent);
}

.skeleton-line {
  height: 10px;
  width: 74%;
  background: linear-gradient(90deg, #eeeeee, #fafafa, #eeeeee);
  background-size: 180% 100%;
  animation: shimmer 1.2s ease infinite;
}

.skeleton-line.wide {
  width: 100%;
}

.skeleton-line.short {
  width: 48%;
}

.error-box {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 10px;
  align-items: center;
}

.error-box svg {
  color: var(--danger);
}

.error-box p {
  margin-top: 4px;
  color: var(--ink-2);
  font-size: 12px;
}

.error-box button {
  min-height: 32px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  padding: 0 12px;
  cursor: pointer;
}

.detail-copy {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 14px 0;
}

.detail-copy p {
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 13.5px;
  line-height: 1.9;
  white-space: pre-wrap;
}

.locked-box {
  border: 1px dashed rgba(255, 87, 34, 0.55);
  background: rgba(255, 87, 34, 0.06);
  padding: 14px;
}

.locked-preview {
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
}

.fade-preview {
  display: grid;
  gap: 7px;
  margin: 12px 0;
  opacity: 0.56;
  mask-image: linear-gradient(#000, transparent);
}

.fade-preview span {
  height: 10px;
  background: rgba(255, 87, 34, 0.22);
}

.fade-preview span:last-child {
  width: 68%;
}

.btn {
  min-height: 32px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  padding: 0 10px;
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
}

.btn.accent {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 800;
}

.source-list {
  display: grid;
  gap: 8px;
  padding-top: 12px;
}

.source-list a {
  display: grid;
  gap: 4px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 10px;
  color: var(--ink);
  text-decoration: none;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.source-list a:hover {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.05);
}

.source-list strong {
  font-size: 13px;
}

.source-list span {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rel-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 12px;
}

.rel-chips button {
  min-height: 32px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  padding: 0 10px;
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
  transition: transform 0.16s ease, border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rel-chips button:hover {
  transform: translateY(-1px);
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(255, 87, 34, 0.05);
}

.rel-chips button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.rel-tag {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 1px 6px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.rel-tag.pre {
  background: rgba(20, 24, 29, 0.06);
  color: var(--ink-2);
}

.rel-tag.post {
  background: rgba(255, 87, 34, 0.12);
  color: var(--accent);
}

.recommend-block .rel-chips button:hover .rel-tag.pre {
  color: var(--accent);
}

.recommend-block .rel-chips button.learned {
  opacity: 0.62;
}

.rel-tag.learned-tag {
  background: rgba(46, 160, 67, 0.14);
  color: #2ea043;
}

.application-block {
  border: 1px solid color-mix(in srgb, var(--accent) 28%, white);
  background: color-mix(in srgb, var(--accent) 4%, var(--panel));
  padding: 14px;
}

.application-loading {
  grid-template-columns: auto 1fr;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes shimmer {
  to {
    background-position: -180% 0;
  }
}

@media (max-width: 920px) {
  .panel.floating {
    position: fixed;
    top: auto;
    left: 10px;
    right: 10px;
    bottom: 10px;
    z-index: 80;
    width: auto;
    min-height: 0;
    max-height: 58vh;
    border: 1px solid rgba(20, 24, 29, 0.1);
    border-radius: 10px;
  }

  .panel.floating.expanded {
    max-height: 76vh;
  }

  .panel.sheet-collapsed {
    flex: 0 0 46px;
    min-height: 46px;
    max-height: 46px;
  }

  .panel.sheet-collapsed .panel-body {
    display: none;
  }
}
</style>
