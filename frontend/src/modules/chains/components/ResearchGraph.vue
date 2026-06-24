<template>
  <div class="research-graph-shell">
    <div v-if="!graph?.nodes.length" class="graph-empty">
      <Network :size="34" />
      <strong>互动图谱将在调研完成后生成</strong>
      <span>每条关系都带来源编号，可在右侧来源标签页核验。</span>
    </div>
    <div v-else ref="canvasRef" class="research-graph"></div>
    <aside v-if="selectedNode" class="node-popover">
      <button type="button" @click="selectedId = null"><X :size="14" /></button>
      <small>{{ selectedNode.type }}</small>
      <strong>{{ selectedNode.name }}</strong>
      <p>{{ selectedNode.summary }}</p>
      <div class="refs">
        <a
          v-for="ref in selectedNode.sourceRefs"
          :key="ref"
          :href="sourceByRef.get(ref)?.url"
          target="_blank"
          rel="noreferrer"
        >{{ ref }}</a>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Network, X } from '@lucide/vue'
import type { ResearchGraph, ResearchSource } from '../researchTypes'

const props = defineProps<{ graph: ResearchGraph | null; sources: ResearchSource[] }>()
const canvasRef = ref<HTMLElement | null>(null)
const selectedId = ref<string | null>(null)
let chart: echarts.ECharts | null = null

const sourceByRef = computed(() => new Map(props.sources.map(source => [source.sourceRef, source])))
const selectedNode = computed(() => props.graph?.nodes.find(node => node.id === selectedId.value) ?? null)

const colors: Record<string, string> = {
  核心对象: '#ff5722',
  上游供应商: '#1565c0',
  材料商: '#607d8b',
  设备商: '#7b1fa2',
  代工厂: '#00897b',
  中游制造: '#2e7d32',
  下游客户: '#ef6c00',
  应用市场: '#c62828',
}

function render() {
  if (!canvasRef.value || !props.graph?.nodes.length) return
  if (!chart) {
    chart = echarts.init(canvasRef.value, undefined, { renderer: 'canvas' })
    chart.on('click', params => {
      if (params.dataType === 'node') selectedId.value = String((params.data as { id: string }).id)
    })
  }
  chart.setOption({
    animationDuration: 700,
    tooltip: {
      formatter: (params: any) => params.dataType === 'edge'
        ? `${params.data.product || params.data.type}<br/>来源：${(params.data.sourceRefs || []).join('、')}`
        : `${params.data.name}<br/>${params.data.type}`,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      symbolSize: 42,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      force: { repulsion: 650, edgeLength: [100, 190], gravity: 0.08 },
      label: { show: true, position: 'bottom', color: '#20242a', fontSize: 11 },
      lineStyle: { color: '#aeb5bd', width: 1.4, curveness: 0.08, opacity: 0.8 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      data: props.graph.nodes.map(node => ({
        ...node,
        itemStyle: { color: colors[node.type] || '#616b75', borderColor: '#fff', borderWidth: 2 },
      })),
      links: props.graph.edges.map(edge => ({
        ...edge,
        source: edge.from,
        target: edge.to,
        label: { show: true, formatter: edge.product || edge.type, fontSize: 9, color: '#69717a' },
      })),
    }],
  }, true)
}

async function refresh() {
  await nextTick()
  if (!props.graph?.nodes.length) {
    chart?.dispose()
    chart = null
    return
  }
  render()
}

function resize() { chart?.resize() }

watch(() => props.graph, () => void refresh(), { deep: true })
onMounted(() => {
  window.addEventListener('resize', resize)
  void refresh()
})
onUnmounted(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<style scoped>
.research-graph-shell { position: relative; height: 100%; min-height: 420px; background: #fafbfc; overflow: hidden; }
.research-graph { width: 100%; height: 100%; min-height: 420px; }
.graph-empty { height: 100%; min-height: 420px; display: grid; place-content: center; justify-items: center; gap: 10px; color: var(--muted); text-align: center; }
.graph-empty svg { color: var(--accent); }
.graph-empty strong { color: var(--ink); font-size: 17px; }
.graph-empty span { font-size: 12px; }
.node-popover { position: absolute; right: 14px; top: 14px; width: min(300px, calc(100% - 28px)); display: grid; gap: 8px; padding: 15px; border: 1px solid var(--line); border-radius: 8px; background: rgba(255,255,255,.96); box-shadow: var(--shadow-md); }
.node-popover button { position: absolute; right: 8px; top: 8px; display: grid; place-items: center; width: 26px; height: 26px; border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.node-popover small { color: var(--accent); font-weight: 800; }
.node-popover strong { padding-right: 22px; }
.node-popover p { margin: 0; color: var(--ink-2); font-size: 12px; line-height: 1.65; }
.refs { display: flex; flex-wrap: wrap; gap: 6px; }
.refs a { padding: 3px 7px; border: 1px solid rgba(255,87,34,.28); border-radius: 99px; color: var(--accent); font-size: 10px; text-decoration: none; }
</style>
