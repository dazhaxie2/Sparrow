<template>
  <div class="chain-list-shell">
    <AppHeader @show-graph="$router.push('/')" />
    <main class="chain-list-page">
    <header class="page-header">
      <h1>产业链专题</h1>
      <p>全球领先公司的供应链网络图谱,数据来源于维基百科词条的 LLM 抽取(粗略草图,非权威商业数据库)。</p>
    </header>

    <section v-if="loading" class="state-box">
      <LoaderCircle class="spin" :size="20" />
      <span>加载产业链清单</span>
    </section>

    <section v-else-if="error" class="state-box error">
      <AlertTriangle :size="20" />
      <span>{{ error }}</span>
      <button type="button" @click="load">重试</button>
    </section>

    <section v-else class="chain-grid">
      <router-link
        v-for="chain in chains"
        :key="chain.slug"
        :to="`/chains/${chain.slug}`"
        class="chain-card"
        :style="{ '--chain-color': chain.coverColor || defaultColor }"
      >
        <div class="card-top">
          <span class="card-badge">{{ chain.nodeCount || 0 }} 节点</span>
          <ArrowRight :size="16" />
        </div>
        <h2>{{ chain.name }}</h2>
        <p>{{ chain.description || '数据采集中' }}</p>
      </router-link>

      <div v-if="!chains.length" class="empty-hint">
        暂无产业链数据。请运行爬虫流水线采集:`python main.py --chains`
      </div>
    </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AlertTriangle, ArrowRight, LoaderCircle } from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import { fetchChains } from '../api'
import type { ChainSummary } from '../types'

const chains = ref<ChainSummary[]>([])
const loading = ref(false)
const error = ref('')
const defaultColor = '#ff5722'

async function load() {
  loading.value = true
  error.value = ''
  try {
    chains.value = await fetchChains()
  } catch (e: any) {
    error.value = e.message || '产业链清单加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.chain-list-shell {
  min-height: 100vh;
  background: var(--surface);
}

.chain-list-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 40px 24px 60px;
}

.page-header h1 {
  font-size: 28px;
  letter-spacing: -0.01em;
}

.page-header p {
  margin-top: 10px;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.8;
}

.state-box {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 32px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  color: var(--ink-2);
  font-size: 14px;
}

.state-box.error {
  border-color: var(--danger);
  color: var(--danger);
}

.state-box button {
  margin-left: auto;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  padding: 6px 14px;
  color: var(--ink);
  cursor: pointer;
}

.chain-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 18px;
  margin-top: 32px;
}

.chain-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  color: var(--ink);
  text-decoration: none;
  transition: transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.chain-card:hover {
  transform: translateY(-3px);
  border-color: var(--chain-color, var(--accent));
  box-shadow: var(--shadow-md);
}

.card-top {
  display: flex;
  align-items: center;
  color: var(--chain-color, var(--accent));
}

.card-badge {
  margin-right: auto;
  padding: 4px 10px;
  border: 1px solid color-mix(in srgb, var(--chain-color, var(--accent)) 35%, white);
  border-radius: 999px;
  background: color-mix(in srgb, var(--chain-color, var(--accent)) 8%, var(--panel));
  font-size: 11px;
  font-weight: 700;
}

.chain-card h2 {
  font-size: 18px;
}

.chain-card p {
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.empty-hint {
  grid-column: 1 / -1;
  padding: 28px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
