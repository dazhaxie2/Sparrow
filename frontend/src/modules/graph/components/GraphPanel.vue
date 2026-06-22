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
          <h2 class="node-title">
            <span v-if="learningActive" class="learning-title-prefix">路径第 {{ learningIndex + 1 }} 步 · </span>
            {{ currentName }}
          </h2>
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

          <section v-if="recommendedNode" class="recommend-box">
            <div>
              <span>推荐下一步</span>
              <strong>{{ recommendedNode.name }}</strong>
              <small>{{ recommendedNode.era }} · {{ recommendedNode.yearLabel }}</small>
            </div>
            <button type="button" @click="$emit('select', recommendedNode.id)">
              <ArrowRight :size="15" />
            </button>
          </section>

          <section v-if="sources.length" class="source-block">
            <div class="section-title">
              <ExternalLink :size="15" />
              <span>资料来源</span>
              <small>{{ sources.length + 1 }}</small>
            </div>
            <div class="source-list">
              <a
                v-for="source in allSources"
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

          <section v-if="detail.prerequisites.length" class="relation-block">
            <div class="section-title">
              <GitPullRequestArrow :size="15" />
              <span>直接前置</span>
              <small>{{ detail.prerequisites.length }}</small>
            </div>
            <div class="rel-chips">
              <button
                v-for="item in detail.prerequisites"
                :key="item.id"
                type="button"
                @click="$emit('select', item.id)"
              >
                {{ item.name }}
              </button>
            </div>
          </section>

          <section v-if="detail.unlocks.length" class="relation-block">
            <div class="section-title">
              <UnlockKeyhole :size="15" />
              <span>后续解锁</span>
              <small>{{ detail.unlocks.length }}</small>
            </div>
            <div class="rel-chips">
              <button
                v-for="item in detail.unlocks"
                :key="item.id"
                type="button"
                @click="$emit('select', item.id)"
              >
                {{ item.name }}
              </button>
            </div>
          </section>
        </template>

        <section v-if="pathNodes.length" class="path-block" :class="{ guiding: learningActive }">
          <div class="section-title">
            <Route :size="15" />
            <span>学习路线</span>
            <small>{{ learningActive ? `第 ${learningIndex + 1} / ${learningTotal} 步` : `${visiblePathCount} / ${pathNodes.length} 步` }}</small>
          </div>
          <p class="path-summary">{{ pathSummary }}</p>
          <div class="path-list">
            <button
              v-for="(node, index) in visiblePathNodes"
              :key="node.id"
              type="button"
              :class="{ current: node.id === currentId, 'guide-current': learningActive && actualPathIndex(index) === learningIndex }"
              :aria-current="learningActive && actualPathIndex(index) === learningIndex ? 'step' : undefined"
              @click="$emit('select', node.id)"
            >
              <span>{{ actualPathIndex(index) + 1 }}</span>
              <strong>{{ node.name }}</strong>
            </button>
          </div>
          <div v-if="hiddenPathCount" class="path-more">
            <button type="button" @click="expandPathWindow">
              继续向前展开 {{ nextPathPageCount }} 步
            </button>
            <span>前面还有 {{ hiddenPathCount }} 步未显示</span>
          </div>
          <button v-if="canCollapsePath" class="path-collapse" type="button" @click="collapsePathWindow">
            收起到最后 {{ PATH_INITIAL_VISIBLE }} 步
          </button>
          <div v-if="learningActive" class="path-controls" aria-label="学习路线导览">
            <button type="button" :disabled="!canGoPrev" @click="$emit('pathPrev')">
              <ArrowLeft :size="14" />
              <span>上一节点</span>
            </button>
            <strong>{{ learningIndex + 1 }} / {{ learningTotal }}</strong>
            <button type="button" :disabled="!canGoNext" @click="$emit('pathNext')">
              <span>下一节点</span>
              <ArrowRight :size="14" />
            </button>
            <button class="ghost" type="button" @click="$emit('pathExit')">
              <X :size="14" />
              <span>退出</span>
            </button>
          </div>
          <button v-else class="path-action" type="button" :disabled="loading" @click="$emit('startPath')">
            <Play :size="14" />
            {{ loading ? '正在整理路线' : '从第一步开始' }}
          </button>
        </section>
      </template>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  BookOpen,
  FileText,
  GitPullRequestArrow,
  GitCompare,
  LoaderCircle,
  LockKeyhole,
  BookmarkPlus,
  ChevronDown,
  ChevronUp,
  CircleCheck,
  ExternalLink,
  PanelRightClose,
  PanelRightOpen,
  Play,
  Route,
  Target,
  UnlockKeyhole,
  X,
} from '@lucide/vue'
import type { NodeBrief, NodeDetail } from '../types'

type ProgressState = 'want' | 'read' | 'mastered' | null
const PATH_INITIAL_VISIBLE = 24
const PATH_PAGE_SIZE = 24

const props = defineProps<{
  detail: NodeDetail | null
  preview: NodeBrief | null
  pathNodes: NodeBrief[]
  loading: boolean
  error: string
  progress: ProgressState
  accentColor: string
  learningActive: boolean
  learningIndex: number
  learningTotal: number
  floating?: boolean
}>()

defineEmits<{
  select: [id: number]
  startPath: []
  retry: []
  pathPrev: []
  pathNext: []
  pathExit: []
  openMember: []
  setProgress: [state: Exclude<ProgressState, null>]
  addCompare: []
}>()

const bodyRef = ref<HTMLElement | null>(null)
const drawerCollapsed = ref(Boolean(props.floating))
const sheetExpanded = ref(false)
const pathVisibleLimit = ref(PATH_INITIAL_VISIBLE)
const current = computed(() => props.detail ?? props.preview)
const currentId = computed(() => current.value?.id)
const currentName = computed(() => current.value?.name ?? '加载中')
const currentEra = computed(() => current.value?.era ?? '未知时代')
const currentYear = computed(() => current.value?.yearLabel ?? '未知年代')
const currentSummary = computed(() => current.value?.summary ?? '正在整理该节点的摘要。')
const currentCategory = computed(() => current.value?.category ?? '')
const currentPremium = computed(() => Boolean(current.value?.premium))
const recommendedNode = computed(() => props.detail?.unlocks?.[0] ?? null)
const sources = computed(() => props.detail?.sources ?? [])

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
const canGoPrev = computed(() => props.learningActive && props.learningIndex > 0)
const canGoNext = computed(() => props.learningActive && props.learningIndex < props.learningTotal - 1)
const pathWindowStart = computed(() => Math.max(props.pathNodes.length - pathVisibleLimit.value, 0))
const visiblePathNodes = computed(() => props.pathNodes.slice(pathWindowStart.value))
const visiblePathCount = computed(() => visiblePathNodes.value.length)
const hiddenPathCount = computed(() => pathWindowStart.value)
const nextPathPageCount = computed(() => Math.min(PATH_PAGE_SIZE, hiddenPathCount.value))
const canCollapsePath = computed(() => pathVisibleLimit.value > PATH_INITIAL_VISIBLE && props.pathNodes.length > PATH_INITIAL_VISIBLE)
const pathSummary = computed(() => {
  const nodes = props.pathNodes
  if (nodes.length <= 12) return nodes.map(node => node.name).join(' -> ')
  const head = nodes.slice(0, 5).map(node => node.name)
  const tail = nodes.slice(-3).map(node => node.name)
  return `${head.join(' -> ')} -> ... -> ${tail.join(' -> ')}`
})
const statusLabel = computed(() => {
  if (props.loading) return 'LOADING'
  if (props.error) return 'RETRY'
  return current.value ? 'SELECTED' : 'WAITING'
})

function expandPathWindow() {
  pathVisibleLimit.value = Math.min(props.pathNodes.length, pathVisibleLimit.value + PATH_PAGE_SIZE)
}

function collapsePathWindow() {
  pathVisibleLimit.value = PATH_INITIAL_VISIBLE
}

function actualPathIndex(visibleIndex: number) {
  return pathWindowStart.value + visibleIndex
}

watch(() => current.value?.id, async () => {
  pathVisibleLimit.value = PATH_INITIAL_VISIBLE
  await nextTick()
  bodyRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
})

watch(() => props.pathNodes.length, () => {
  pathVisibleLimit.value = Math.min(pathVisibleLimit.value, Math.max(PATH_INITIAL_VISIBLE, props.pathNodes.length))
})

watch(() => props.learningIndex, index => {
  if (!props.learningActive || index < 0) return
  const distanceBeforeWindow = pathWindowStart.value - index
  if (distanceBeforeWindow > 0 && distanceBeforeWindow <= PATH_PAGE_SIZE) {
    pathVisibleLimit.value = Math.min(props.pathNodes.length, pathVisibleLimit.value + PATH_PAGE_SIZE)
  }
})

watch(() => props.floating, value => {
  drawerCollapsed.value = Boolean(value)
})
</script>

<style scoped>
.panel {
  width: 372px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.panel.floating {
  position: fixed;
  top: 92px;
  right: 32px;
  bottom: 32px;
  z-index: 80;
  width: min(392px, calc(100vw - 64px));
  border: 1px solid var(--ink);
  box-shadow: var(--shadow-md);
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
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

.panel-header div {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
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
  border: 1px solid var(--line-strong);
  padding: 3px 7px;
  color: var(--muted);
  font-size: 10px;
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
.path-block,
.relation-block,
.detail-copy,
.locked-box,
.loading-box,
.error-box,
.recommend-box,
.source-block {
  margin: 18px;
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
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: 0;
}

.learning-title-prefix {
  color: var(--accent);
  font-size: 15px;
  font-weight: 900;
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

.path-block.guiding {
  border: 1px solid rgba(255, 87, 34, 0.35);
  background: rgba(255, 87, 34, 0.04);
  padding: 14px;
}

.path-list {
  display: grid;
  gap: 7px;
  padding-top: 12px;
}

.path-summary {
  margin-top: 11px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.7;
}

.path-list button {
  display: grid;
  grid-template-columns: 24px 1fr;
  align-items: center;
  gap: 9px;
  min-height: 34px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 0 9px;
  color: var(--ink-2);
  text-align: left;
  cursor: pointer;
  transition: transform 0.16s ease, border-color 0.16s ease, background 0.16s ease;
}

.path-list button:hover,
.path-list button.current,
.path-list button.guide-current {
  transform: translateX(2px);
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.06);
  color: var(--ink);
}

.path-list button.guide-current {
  box-shadow: 0 0 0 2px rgba(255, 87, 34, 0.16);
}

.path-list button.guide-current span {
  background: var(--accent);
}

.path-list span {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  background: var(--ink);
  color: var(--bg);
  font-size: 11px;
  font-weight: 800;
}

.path-list strong {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path-more,
.path-collapse {
  margin-top: 10px;
}

.path-more {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.path-more button,
.path-collapse {
  min-height: 30px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 10px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.path-more button:hover,
.path-collapse:hover {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.06);
  color: var(--accent);
}

.path-more span {
  color: var(--muted);
  font-size: 12px;
}

.path-action {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 10px;
  border: 1px solid var(--accent);
  background: rgba(255, 87, 34, 0.08);
  color: var(--accent);
  padding: 0 11px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.path-action:disabled {
  border-color: var(--line-strong);
  background: #e7e7e7;
  color: var(--muted);
  cursor: default;
}

.path-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.path-controls button {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  padding: 0 10px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.path-controls button:disabled {
  border-color: var(--line-strong);
  background: #e7e7e7;
  color: var(--muted);
  cursor: default;
}

.path-controls .ghost {
  border-color: var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
}

.path-controls strong {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  border: 1px solid rgba(255, 87, 34, 0.28);
  background: rgba(255, 87, 34, 0.08);
  color: var(--accent);
  padding: 0 10px;
  font-size: 12px;
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

.error-box button,
.recommend-box button {
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

.recommend-box {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(255, 87, 34, 0.35);
  background: rgba(255, 87, 34, 0.05);
  padding: 12px;
}

.recommend-box div {
  display: grid;
  gap: 4px;
}

.recommend-box span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.recommend-box strong {
  font-size: 15px;
}

.recommend-box small {
  color: var(--muted);
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
  .panel,
  .panel.floating {
    position: sticky;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 40;
    width: 100%;
    min-height: 42vh;
    max-height: 62vh;
    border-left: 0;
    border-top: 1px solid var(--line);
  }

  .panel.expanded,
  .panel.floating.expanded {
    max-height: 84vh;
  }

  .panel.sheet-collapsed {
    flex: 0 0 46px;
    min-height: 46px;
    max-height: 46px;
  }

  .panel.sheet-collapsed .panel-body {
    display: none;
  }

  .sheet-toggle {
    display: grid;
  }

  .path-controls {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .path-controls button,
  .path-controls strong {
    width: 100%;
  }
}
</style>
