<template>
  <div class="agent-forum">
    <div v-if="researching" class="forum-status">
      <LoaderCircle class="spin" :size="14" />
      <span>行业 / 检索 / 洞察 Agent 正在并行调研，论坛主持人定期汇总观点与分歧</span>
    </div>

    <div v-if="!events.length && !researching" class="forum-empty">
      <MessagesSquare :size="30" />
      <strong>调研过程将在这里实时呈现</strong>
      <span>启动 Multi-Agent 调研后，各 Agent 的发言与论坛主持人的汇总会以对话形式流式展示。</span>
    </div>

    <ol v-else ref="stream" class="forum-stream">
      <li v-for="(event, index) in events" :key="`${event.id}-${index}`" class="forum-item" :class="`src-${event.source.toLowerCase()}`">
        <div class="bubble">
          <div class="bubble-head">
            <span class="bubble-source">{{ event.sourceText || event.source }}</span>
            <time v-if="event.createdAt">{{ event.createdAt }}</time>
          </div>
          <div class="bubble-body">{{ event.content }}</div>
        </div>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { LoaderCircle, MessagesSquare } from '@lucide/vue'
import type { ForumEventView, ForumSsePayload } from '../model/types'

const props = defineProps<{ researching: boolean }>()
const events = ref<ForumEventView[]>([])
const stream = ref<HTMLElement | null>(null)

/** 用初始历史事件初始化(工作台初次加载还原协作流)。 */
function setHistory(history: ForumEventView[]) {
  events.value = [...history]
  void scrollBottom()
}

/** 追加一条 SSE 实时事件。 */
function append(payload: ForumSsePayload) {
  const e = payload.event
  events.value.push({
    id: Date.now() + Math.random(),
    source: e.source,
    sourceText: sourceText(e.source),
    content: e.content,
    createdAt: e.createdAt,
  })
  void scrollBottom()
}

function sourceText(source: string) {
  return ({ INDUSTRY: '行业 Agent', QUERY: '检索 Agent', INSIGHT: '洞察 Agent', HOST: '论坛主持人', SYSTEM: '系统' } as Record<string, string>)[source] || source
}

function scrollBottom() {
  void nextTick(() => {
    if (stream.value) stream.value.scrollTop = stream.value.scrollHeight
  })
}

defineExpose({ setHistory, append })
</script>

<style scoped>
.agent-forum { height: 100%; min-height: 360px; display: flex; flex-direction: column; background: var(--surface); overflow: hidden; }
.forum-status { display: flex; align-items: center; gap: 8px; padding: 9px 16px; border-bottom: 1px solid var(--line); background: #fff3e9; color: #e65100; font-size: 12px; }
.forum-empty { flex: 1; display: grid; place-content: center; justify-items: center; gap: 9px; color: var(--muted); text-align: center; }
.forum-empty svg { color: var(--accent); }
.forum-empty strong { color: var(--ink); font-size: 16px; }
.forum-empty span { max-width: 380px; font-size: 12px; }
.forum-stream { flex: 1; list-style: none; margin: 0; padding: 16px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.forum-item { display: flex; }
.forum-item.src-host { justify-content: center; }
.forum-item.src-system { justify-content: center; }
.bubble { max-width: 86%; padding: 10px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.src-industry .bubble { border-color: #ffd1b3; background: #fff7f2; }
.src-query .bubble { border-color: #bcdcff; background: #f3f9ff; }
.src-insight .bubble { border-color: #d6e5cc; background: #f5faf2; }
.src-host .bubble { max-width: 94%; border-color: var(--accent); background: #fff; border-left: 4px solid var(--accent); }
.src-system .bubble { border-style: dashed; color: var(--muted); font-size: 11px; }
.bubble-head { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.bubble-source { font-size: 11px; font-weight: 800; color: var(--accent); }
.src-host .bubble-source { color: var(--ink); }
.bubble-head time { margin-left: auto; color: var(--muted); font-size: 10px; }
.bubble-body { color: var(--ink-2); font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.spin { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
