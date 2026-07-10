<template>
  <div class="agent-forum">
    <div v-if="researching" class="forum-status">
      <LoaderCircle class="spin" :size="14" />
      <span>行业 / 检索 / 洞察 Agent 正在并行调研，论坛主持人定期汇总观点与分歧</span>
    </div>

    <div v-if="error && !researching" class="forum-error">
      <AlertTriangle :size="15" />
      <div><strong>本轮调研未完成</strong><span>{{ error }} 已完成的过程记录仍保留在下方。</span></div>
    </div>

    <div v-if="!events.length && !researching" class="forum-empty">
      <MessagesSquare :size="30" />
      <strong>调研过程将在这里实时呈现</strong>
      <span>启动 Multi-Agent 调研后，各 Agent 的发言与论坛主持人的汇总会以对话形式流式展示。</span>
    </div>

    <ol v-else ref="stream" class="forum-stream">
      <li
        v-for="(event, index) in events"
        :key="`${event.id}-${index}`"
        class="forum-item"
        :class="`src-${event.source.toLowerCase()}`"
      >
        <div class="bubble">
          <div class="bubble-head">
            <span class="bubble-source">{{ event.sourceText || event.source }}</span>
            <time v-if="event.createdAt">{{ formatChinaTime(event.createdAt) }}</time>
          </div>
          <div class="bubble-body">{{ event.content }}</div>
        </div>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { AlertTriangle, LoaderCircle, MessagesSquare } from '@lucide/vue'
import type { ForumEventView } from '../model/types'

const props = defineProps<{ researching: boolean; events: ForumEventView[]; error?: string | null }>()
const stream = ref<HTMLElement | null>(null)

function formatChinaTime(value: string) {
  const legacyTime = /^(\d{2}):(\d{2}):(\d{2})$/.exec(value)
  const parsed = legacyTime
    ? new Date(Date.UTC(
        new Date().getUTCFullYear(),
        new Date().getUTCMonth(),
        new Date().getUTCDate(),
        Number(legacyTime[1]),
        Number(legacyTime[2]),
        Number(legacyTime[3]),
      ))
    : new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(parsed)
}

function scrollBottom() {
  void nextTick(() => {
    if (stream.value) stream.value.scrollTop = stream.value.scrollHeight
  })
}

watch(() => props.events.length, () => void scrollBottom(), { immediate: true })
</script>

<style scoped>
.agent-forum { height: 100%; min-height: 360px; display: flex; flex-direction: column; background: var(--surface); overflow: hidden; }
.forum-status { display: flex; align-items: center; gap: 8px; padding: 9px 16px; border-bottom: 1px solid var(--line); background: #fff3e9; color: #e65100; font-size: 12px; }
.forum-error { display: flex; gap: 9px; padding: 10px 16px; border-bottom: 1px solid #ffd4cc; background: #fff5f2; color: var(--danger); }
.forum-error div { display: grid; gap: 2px; }
.forum-error strong { font-size: 12px; }
.forum-error span { color: var(--ink-2); font-size: 11px; }
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
