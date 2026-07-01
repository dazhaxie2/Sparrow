<template>
  <div class="chart-card">
    <div v-if="widget.title" class="chart-title">{{ widget.title }}</div>
    <div ref="hostRef" class="chart-host"></div>
    <details v-if="fallbackRows.length" class="chart-fallback">
      <summary>数据表格(图表兜底)</summary>
      <table>
        <thead><tr><th v-for="(h, i) in fallbackHeaders" :key="i">{{ h }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, i) in fallbackRows" :key="i">
            <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </details>
  </div>
</template>

<script setup lang="ts">
// 按需引入 echarts，避免全量打包。
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { Widget } from '../model/types'

echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, CanvasRenderer])

const props = defineProps<{ widget: Widget }>()
const hostRef = ref<HTMLElement | null>(null)
let chart: ReturnType<typeof echarts.init> | null = null

// widgetType 形如 "chart.js/bar" 或 "bar"；映射到 echarts 类型
const echartsType = computed(() => {
  const raw = (props.widget.widgetType || '').toLowerCase()
  if (raw.includes('pie') || raw.includes('doughnut')) return 'pie'
  if (raw.includes('line')) return 'line'
  return 'bar'
})

/** 把 IR widget.data(尽量兼容 Chart.js 风格 {labels, datasets})转 echarts option。 */
const option = computed(() => buildOption(props.widget.data, echartsType.value))

const fallbackHeaders = computed<string[]>(() => {
  const data = props.widget.data as any
  return ['类别', ...(data?.datasets?.map((d: any) => d.label || '值') ?? ['值'])]
})
const fallbackRows = computed(() => {
  const data = props.widget.data as any
  const labels: any[] = data?.labels ?? []
  const datasets: any[] = data?.datasets ?? []
  return labels.map((label, i) => [label, ...datasets.map(d => d.data?.[i] ?? '')])
})

function buildOption(data: unknown, type: string): Record<string, unknown> {
  const d = (data ?? {}) as { labels?: unknown; datasets?: { label?: string; data?: unknown[] }[] }
  const labels = (d.labels as string[]) ?? []
  const datasets = d.datasets ?? []
  if (type === 'pie') {
    return {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      series: [{ type: 'pie', radius: ['38%', '66%'], data: labels.map((label, i) => ({ name: label, value: Number(datasets[0]?.data?.[i] ?? 0) })) }],
    }
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, type: 'scroll' },
    grid: { left: 40, right: 16, top: 20, bottom: 40 },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value' },
    series: datasets.map(ds => ({ name: ds.label ?? '', type, data: (ds.data ?? []).map((v) => Number(v)) })),
  }
}

function render() {
  if (!hostRef.value) return
  if (!chart) chart = echarts.init(hostRef.value, undefined, { renderer: 'canvas' })
  chart.setOption(option.value, true)
}

function resize() { chart?.resize() }

watch(() => props.widget, async () => { await nextTick(); render() }, { deep: true })
onMounted(() => { window.addEventListener('resize', resize); void nextTick().then(render) })
onUnmounted(() => { window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<style scoped>
.chart-card { margin: 0 0 16px; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); }
.chart-title { font-size: 13px; font-weight: 800; margin-bottom: 8px; }
.chart-host { width: 100%; height: 240px; }
.chart-fallback { margin-top: 8px; font-size: 11px; color: var(--muted); }
.chart-fallback table { width: 100%; border-collapse: collapse; margin-top: 6px; }
.chart-fallback th, .chart-fallback td { padding: 4px 8px; border: 1px solid var(--line); text-align: left; }
</style>
