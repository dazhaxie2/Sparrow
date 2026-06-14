import { ref } from 'vue'
import { askAi } from '../api/chat'
import type { ChatMessage } from '../types'

const WELCOME: ChatMessage = {
  role: 'assistant',
  content:
    '### 结论\n你好，我是科技树 AI 向导。选择一个节点后，我会带着当前上下文回答前置知识、重要性和下一步学习建议。\n\n### 下一步\n- 点击图谱节点，或直接输入你想理解的技术问题。',
  mode: 'guide',
  format: 'markdown:v1',
}

export function useChat() {
  const messages = ref<ChatMessage[]>([{ ...WELCOME }])
  const loading = ref(false)
  const phase = ref('')

  async function ask(question: string, isLoggedIn: boolean): Promise<{ quotaMsg?: string }> {
    const trimmed = question.trim()
    if (!trimmed) return {}
    if (!isLoggedIn) {
      messages.value.push({
        role: 'assistant',
        content: '### 结论\n请先登录后再提问。\n\n### 下一步\n- 登录后可以继续使用 AI 向导。',
        mode: 'error',
        format: 'markdown:v1',
        timestamp: Date.now(),
      })
      return {}
    }

    messages.value.push({ role: 'user', content: trimmed, timestamp: Date.now() })
    loading.value = true
    phase.value = '正在检索图谱上下文'

    try {
      await new Promise(resolve => window.setTimeout(resolve, 160))
      phase.value = '正在生成回答'
      const res = await askAi(trimmed)
      messages.value.push({
        role: 'assistant',
        content: res.answer,
        mode: res.mode,
        format: res.format,
        intent: res.intent,
        sources: res.sources,
        steps: res.steps,
        timestamp: Date.now(),
      })
      let quotaMsg: string | undefined
      if (res.remainingQuota >= 0) {
        quotaMsg = `今日剩余免费次数: ${res.remainingQuota}（会员不限次）`
      }
      return { quotaMsg }
    } catch (e: any) {
      const message = e.code === 429 ? e.message : `出错了：${e.message}`
      messages.value.push({
        role: 'assistant',
        content: `### 结论\n${message}\n\n### 下一步\n- 请稍后重试，或换一个更具体的问题。`,
        mode: 'error',
        format: 'markdown:v1',
        timestamp: Date.now(),
      })
      return {}
    } finally {
      loading.value = false
      phase.value = ''
    }
  }

  function clearMessages() {
    messages.value = [{ ...WELCOME }]
  }

  return { messages, loading, phase, ask, clearMessages }
}
