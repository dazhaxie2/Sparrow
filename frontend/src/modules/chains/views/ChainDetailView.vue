<template>
  <div class="chain-detail-shell">
    <AppHeader @show-graph="$router.push('/')" />
    <main class="chain-detail-page">
    <header class="detail-header">
      <router-link to="/chains" class="back-link">
        <ArrowLeft :size="16" />
        <span>产业链列表</span>
      </router-link>
      <h1>{{ chain?.name || slug }}</h1>
      <p v-if="graph">{{ graph.nodes.length }} 个节点 · {{ graph.edges.length }} 条供应关系</p>
    </header>

    <div class="detail-body">
      <div ref="canvasRef" class="chain-canvas"></div>

      <aside class="node-panel">
        <template v-if="loading">
          <LoaderCircle class="spin" :size="18" />
          <strong>正在加载供应链网络</strong>
        </template>
        <template v-else-if="error">
          <AlertTriangle :size="18" />
          <strong>加载失败</strong>
          <p>{{ error }}</p>
          <button class="retry-button" type="button" @click="load">重试</button>
        </template>
        <template v-else-if="!graph || !graph.nodes.length">
          <strong>暂无数据</strong>
          <p>该产业链尚未采集。请运行爬虫流水线,或稍后再试。</p>
        </template>
        <template v-else-if="selectedNode">
          <span class="type-badge" :style="{ background: typeColor(selectedNode.nodeType) }">
            {{ selectedNode.nodeTypeText }}
          </span>
          <h2>{{ selectedNode.name }}</h2>
          <p v-if="selectedNode.summary" class="summary">{{ selectedNode.summary }}</p>
          <p v-else class="summary muted">暂无摘要</p>

          <div v-if="suppliers.length" class="rel-section">
            <small>上游供应商({{ suppliers.length }})</small>
            <div class="chips">
              <span v-for="s in suppliers" :key="s.from">{{ s.name }}</span>
            </div>
          </div>
          <div v-if="customers.length" class="rel-section">
            <small>下游客户({{ customers.length }})</small>
            <div class="chips">
              <span v-for="c in customers" :key="c.to">{{ c.name }}</span>
            </div>
          </div>
        </template>
        <template v-else>
          <strong>点击节点查看详情</strong>
          <p class="muted">选择网络图中的任意公司,查看其上下游供应关系。</p>
        </template>
      </aside>
    </div>

    <div class="legend">
      <span v-for="(color, type) in NODE_TYPE_COLORS" :key="type" class="legend-item">
        <i :style="{ background: color }"></i>{{ type }}
      </span>
    </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, ArrowLeft, LoaderCircle } from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import { fetchChain, fetchChainGraph } from '../api'
import { NODE_TYPE_COLORS } from '../types'
import type { ChainDetail, ChainGraph } from '../types'
import { useChainGraph } from '../composables/useChainGraph'

const props = defineProps<{ slug: string }>()

const chain = ref<ChainDetail | null>(null)
const graph = ref<ChainGraph | null>(null)
const loading = ref(false)
const error = ref('')
const canvasRef = ref<HTMLElement | null>(null)

const { chart, selectedNodeId, setGraph } = useChainGraph()

const nodeById = computed(() => new Map((graph.value?.nodes ?? []).map((n) => [n.id, n])))
const selectedNode = computed(() =>
  selectedNodeId.value != null ? nodeById.value.get(selectedNodeId.value) ?? null : null,
)

// 选中节点的上下游(基于有向边:from→to 表示 from 供货给 to)。
const suppliers = computed(() => {
  if (!selectedNodeId.value || !graph.value) return []
  return graph.value.edges
    .filter((e) => e.to === selectedNodeId.value)
    .map((e) => ({ ...e, name: nodeById.value.get(e.from)?.name ?? '' }))
})
const customers = computed(() => {
  if (!selectedNodeId.value || !graph.value) return []
  return graph.value.edges
    .filter((e) => e.from === selectedNodeId.value)
    .map((e) => ({ ...e, name: nodeById.value.get(e.to)?.name ?? '' }))
})

function typeColor(type: string | null) {
  return (type && NODE_TYPE_COLORS[type]) || '#8a8a8a'
}

async function load() {
  loading.value = true
  error.value = ''
  chain.value = null
  graph.value = null
  chart.dispose()
  try {
    const [detail, graphData] = await Promise.all([
      fetchChain(props.slug),
      fetchChainGraph(props.slug),
    ])
    chain.value = detail
    graph.value = graphData
    setGraph(graphData)
    await nextTick()
    if (canvasRef.value) {
      chart.dispose()
      chart.init(canvasRef.value)
      chart.renderTree()
      requestAnimationFrame(() => chart.fitView())
    }
  } catch (e: any) {
    error.value = e.message || '供应链网络加载失败'
  } finally {
    loading.value = false
  }
}

function handleResize() {
  chart.resize()
}

watch(() => props.slug, () => void load())

onMounted(() => {
  window.addEventListener('resize', handleResize)
  void load()
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chart.dispose()
})
</script>

<style scoped>
.chain-detail-shell {
  height: 100vh;
  overflow: hidden;
}

.chain-detail-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 52px);
  min-height: 0;
}

.detail-header {
  padding: 16px 24px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-2);
  font-size: 12px;
  text-decoration: none;
}

.back-link:hover { color: var(--accent); }

.detail-header h1 {
  margin-top: 8px;
  font-size: 22px;
}

.detail-header p {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}

.detail-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.chain-canvas {
  flex: 1;
  min-width: 0;
  background: var(--surface);
}

.node-panel {
  width: 320px;
  flex-shrink: 0;
  padding: 20px;
  border-left: 1px solid var(--line);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.type-badge {
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: 999px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.node-panel h2 {
  font-size: 18px;
}

.retry-button {
  align-self: flex-start;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 6px 12px;
  cursor: pointer;
}

.summary {
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.8;
}

.summary.muted, .muted { color: var(--muted); }

.rel-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}

.rel-section small {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chips span {
  padding: 4px 10px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  font-size: 12px;
}

.legend {
  display: flex;
  gap: 18px;
  padding: 10px 24px;
  border-top: 1px solid var(--line);
  background: var(--panel);
  flex-shrink: 0;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-2);
  font-size: 11px;
}

.legend-item i {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
}

.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 920px) {
  .chain-detail-page { height: calc(100vh - 48px); }
  .node-panel { width: 260px; }
}
</style>
