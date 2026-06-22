<template>
  <aside class="side-rail" aria-label="知识图谱控制台">
    <div class="rail-head">
      <span class="accent-tag">SPARROW KNOWLEDGE GRAPH</span>
      <h1>技术关系图谱</h1>
      <p>从领域与连接关系发现知识结构</p>
    </div>

    <div class="rail-stats" aria-label="图谱指标">
      <div class="stat"><strong>{{ totalNodes }}</strong><span>节点</span></div>
      <div class="stat"><strong>{{ totalEdges }}</strong><span>关系</span></div>
      <div class="stat"><strong>{{ masteredCount }}</strong><span>已掌握</span></div>
      <div class="stat"><strong>{{ premiumCount }}</strong><span>深度</span></div>
    </div>

    <div class="rail-scroll">
      <section v-if="categories.length" class="rail-section">
        <div class="rail-label"><Layers :size="13" /><span>领域</span></div>
        <div class="rail-chips">
          <button type="button" :class="{ active: activeCategory === null }" @click="$emit('setCategory', null)">全部</button>
          <button
            v-for="cat in categories"
            :key="cat"
            type="button"
            :class="{ active: activeCategory === cat }"
            @click="$emit('setCategory', cat)"
          >{{ cat }}</button>
        </div>
      </section>

      <section class="rail-section">
        <div class="rail-label"><span>显示密度</span></div>
        <div class="rail-density">
          <button
            v-for="opt in limitOptions"
            :key="opt"
            type="button"
            :class="{ active: graphLimit === opt }"
            @click="$emit('setLimit', opt)"
          >{{ opt }}</button>
        </div>
      </section>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Layers } from '@lucide/vue'

defineProps<{
  totalNodes: number
  totalEdges: number
  masteredCount: number
  premiumCount: number
  categories: string[]
  activeCategory: string | null
  limitOptions: number[]
  graphLimit: number
}>()

defineEmits<{
  setCategory: [cat: string | null]
  setLimit: [limit: number]
}>()
</script>

<style scoped>
.side-rail {
  flex: 0 0 244px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.rail-head {
  padding: 16px 16px 12px;
  border-bottom: 1px solid var(--line);
}

.accent-tag {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.rail-head h1 {
  margin-top: 9px;
  font-size: 20px;
  line-height: 1.15;
}

.rail-head p {
  margin-top: 5px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.rail-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
}

.rail-stats .stat {
  display: grid;
  gap: 3px;
  padding: 6px 0;
}

.rail-stats strong {
  font-size: 20px;
  line-height: 1;
}

.rail-stats span {
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.rail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 0;
}

.rail-section {
  padding: 12px 16px;
}

.rail-section + .rail-section {
  border-top: 1px solid var(--line);
}

.rail-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.rail-label svg {
  color: var(--accent);
}

.rail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rail-chips button {
  min-height: 28px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rail-chips button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.rail-chips button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 700;
}

.rail-density {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.rail-density button {
  min-height: 30px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rail-density button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.rail-density button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 700;
}

@media (max-width: 920px) {
  .side-rail {
    flex: none;
    width: 100%;
    min-height: 0;
    border-radius: 8px;
  }

  .rail-head {
    display: none;
  }

  .rail-stats {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    padding: 8px 12px;
  }

  .rail-stats .stat {
    padding: 3px 0;
    text-align: center;
  }

  .rail-stats strong {
    font-size: 16px;
  }

  .rail-scroll {
    display: flex;
    overflow-x: auto;
    padding: 0;
  }

  .rail-section {
    flex: none;
    min-width: max-content;
    padding: 8px 12px;
  }

  .rail-section + .rail-section {
    border-top: 0;
    border-left: 1px solid var(--line);
  }

  .rail-label {
    margin-bottom: 7px;
  }
}
</style>
