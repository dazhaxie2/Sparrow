<template>
  <div class="block-view">
    <!-- heading -->
    <h2 v-if="block.type === 'heading' && block.level <= 2" :id="block.anchor" class="b-heading">{{ block.text }}</h2>
    <h3 v-else-if="block.type === 'heading' && block.level === 3" :id="block.anchor" class="b-heading">{{ block.text }}</h3>
    <h4 v-else-if="block.type === 'heading'" :id="block.anchor" class="b-heading">{{ block.text }}</h4>

    <!-- paragraph -->
    <p v-else-if="block.type === 'paragraph'" class="b-para"><InlineRuns :inlines="block.inlines" :sources="sources" @source-click="emit('source-click', $event)" /></p>

    <!-- blockquote -->
    <blockquote v-else-if="block.type === 'blockquote'" class="b-quote"><InlineRuns :inlines="block.inlines" :sources="sources" @source-click="emit('source-click', $event)" /></blockquote>

    <hr v-else-if="block.type === 'hr'" class="b-hr" />

    <!-- list -->
    <ul v-else-if="block.type === 'list'" class="b-list">
      <li v-for="(item, i) in block.items" :key="i">
        <BlockView v-for="(sub, j) in item" :key="j" :block="sub" :sources="sources" @source-click="emit('source-click', $event)" />
      </li>
    </ul>

    <!-- callout -->
    <div v-else-if="block.type === 'callout'" :class="['b-callout', `tone-${block.tone || 'info'}`]">
      <div v-if="block.text" class="callout-title">{{ block.text }}</div>
      <div class="callout-body">
        <template v-for="(item, i) in block.items" :key="i">
          <BlockView v-for="(sub, j) in item" :key="`${i}-${j}`" :block="sub" :sources="sources" @source-click="emit('source-click', $event)" />
        </template>
      </div>
    </div>

    <!-- table -->
    <div v-else-if="block.type === 'table'" class="b-table-wrap">
      <div v-if="block.rows.caption" class="b-table-caption">{{ block.rows.caption }}</div>
      <table class="b-table">
        <thead v-if="block.rows.header?.length">
          <tr v-for="(row, i) in block.rows.header" :key="`h${i}`">
            <th v-for="(cell, j) in row" :key="j"><InlineRuns :inlines="cell" :sources="sources" @source-click="emit('source-click', $event)" /></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in (block.rows.body ?? [])" :key="`b${i}`">
            <td v-for="(cell, j) in row" :key="j"><InlineRuns :inlines="cell" :sources="sources" @source-click="emit('source-click', $event)" /></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- SWOT -->
    <div v-else-if="block.type === 'swotTable'" class="swot-grid">
      <div class="swot-cell tone-strength">
        <div class="swot-head">优势 S</div>
        <SwotItemView v-for="(item, i) in block.swot.strengths" :key="`s${i}`" :item="item" />
      </div>
      <div class="swot-cell tone-weakness">
        <div class="swot-head">劣势 W</div>
        <SwotItemView v-for="(item, i) in block.swot.weaknesses" :key="`w${i}`" :item="item" />
      </div>
      <div class="swot-cell tone-opportunity">
        <div class="swot-head">机会 O</div>
        <SwotItemView v-for="(item, i) in block.swot.opportunities" :key="`o${i}`" :item="item" />
      </div>
      <div class="swot-cell tone-threat">
        <div class="swot-head">威胁 T</div>
        <SwotItemView v-for="(item, i) in block.swot.threats" :key="`t${i}`" :item="item" />
      </div>
    </div>

    <!-- PEST -->
    <div v-else-if="block.type === 'pestTable'" class="pest-grid">
      <div class="pest-card tone-political">
        <div class="pest-head">政治 P</div>
        <PestItemView v-for="(item, i) in block.pest.political" :key="`p${i}`" :item="item" />
      </div>
      <div class="pest-card tone-economic">
        <div class="pest-head">经济 E</div>
        <PestItemView v-for="(item, i) in block.pest.economic" :key="`e${i}`" :item="item" />
      </div>
      <div class="pest-card tone-social">
        <div class="pest-head">社会 S</div>
        <PestItemView v-for="(item, i) in block.pest.social" :key="`so${i}`" :item="item" />
      </div>
      <div class="pest-card tone-technological">
        <div class="pest-head">技术 T</div>
        <PestItemView v-for="(item, i) in block.pest.technological" :key="`t${i}`" :item="item" />
      </div>
    </div>

    <!-- KPI -->
    <div v-else-if="block.type === 'kpiGrid'" class="kpi-grid" :style="{ gridTemplateColumns: `repeat(${block.kpi.cols || 4}, 1fr)` }">
      <div v-for="(item, i) in block.kpi.items" :key="i" class="kpi-card">
        <div class="kpi-label">{{ item.label }}</div>
        <div class="kpi-value">{{ item.value }}<span v-if="item.unit" class="kpi-unit">{{ item.unit }}</span></div>
        <div v-if="item.delta" :class="['kpi-delta', `delta-${item.deltaTone || 'neutral'}`]">{{ item.delta }}</div>
      </div>
    </div>

    <!-- widget -->
    <ChartWidget v-else-if="block.type === 'widget'" :widget="block.widget" />
  </div>
</template>

<script setup lang="ts">
import type { Block, ResearchSource } from '../model/types'
import InlineRuns from './InlineRuns.vue'
import SwotItemView from './SwotItemView.vue'
import PestItemView from './PestItemView.vue'
import ChartWidget from './ChartWidget.vue'

defineProps<{ block: Block; sources: ResearchSource[] }>()
const emit = defineEmits<{ (e: 'source-click', ref: string): void }>()
</script>

<style scoped>
.b-heading { margin: 22px 0 10px; font-size: 16px; scroll-margin-top: 16px; }
.b-para { margin: 0 0 12px; color: var(--ink-2); font-size: 14px; line-height: 1.85; }
.b-quote { margin: 0 0 12px; padding: 10px 14px; border-left: 3px solid var(--accent); background: var(--surface); color: var(--ink-2); font-size: 14px; }
.b-hr { border: 0; border-top: 1px solid var(--line); margin: 18px 0; }
.b-list { margin: 0 0 12px; padding-left: 22px; color: var(--ink-2); font-size: 14px; line-height: 1.8; }
.b-list li { margin: 4px 0; }
.b-callout { margin: 0 0 14px; padding: 14px 16px; border-radius: 8px; border: 1px solid var(--line); }
.b-callout.tone-info { background: #f0f7ff; border-color: #bcdcff; }
.b-callout.tone-warning { background: #fff8e6; border-color: #ffe08a; }
.b-callout.tone-success { background: #ecfbee; border-color: #b7e8b9; }
.b-callout.tone-danger { background: #fff0f0; border-color: #ffcaca; }
.callout-title { font-weight: 800; margin-bottom: 6px; }
.callout-body :deep(p) { margin: 4px 0; font-size: 13px; color: var(--ink-2); }
.b-table-wrap { margin: 0 0 16px; overflow-x: auto; }
.b-table-caption { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.b-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.b-table th, .b-table td { padding: 9px 12px; border: 1px solid var(--line); text-align: left; }
.b-table th { background: var(--surface); font-weight: 700; }
.b-table tbody tr:nth-child(even) { background: rgba(0,0,0,.015); }
.swot-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 0 0 16px; }
.swot-cell { padding: 14px; border-radius: 8px; border: 1px solid var(--line); }
.swot-cell.tone-strength { background: #ecfbee; } .swot-cell.tone-weakness { background: #fff0f0; }
.swot-cell.tone-opportunity { background: #f0f7ff; } .swot-cell.tone-threat { background: #fff8e6; }
.swot-head { font-weight: 800; font-size: 13px; margin-bottom: 8px; }
.pest-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin: 0 0 16px; }
.pest-card { padding: 14px; border-radius: 8px; border: 1px solid var(--line); }
.pest-card.tone-political { background: #f3f0ff; } .pest-card.tone-economic { background: #ecfbee; }
.pest-card.tone-social { background: #fff8e6; } .pest-card.tone-technological { background: #f0f7ff; }
.pest-head { font-weight: 800; font-size: 13px; margin-bottom: 8px; }
.kpi-grid { display: grid; gap: 10px; margin: 0 0 16px; }
.kpi-card { padding: 12px 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); }
.kpi-label { font-size: 11px; color: var(--muted); }
.kpi-value { margin-top: 4px; font-size: 22px; font-weight: 800; color: var(--accent); }
.kpi-unit { font-size: 13px; margin-left: 3px; color: var(--ink-2); }
.kpi-delta { font-size: 11px; margin-top: 2px; }
.kpi-delta.delta-up { color: #237a3b; } .kpi-delta.delta-down { color: var(--danger); }
@media (max-width: 720px) { .swot-grid { grid-template-columns: 1fr; } }
</style>
