import { ref } from 'vue'
import { askAi } from '../api/chat'
import type { ChatMessage } from '../types'

export function useChat() {
  const messages = ref<ChatMessage[]>([
    { role: 'assistant', content: '你好!我是科技树 AI 向导。试试问我:「蒸汽机的前置技术有哪些?」' }
  ])
  const loading = ref(false)

  async function ask(question: string, isLoggedIn: boolean): Promise<{ quotaMsg?: string }> {
    if (!question.trim()) return {}
    if (!isLoggedIn) {
      messages.value.push({ role: 'assistant', content: '请先登录后再提问' })
      return {}
    }

    messages.value.push({ role: 'user', content: question, timestamp: Date.now() })
    loading.value = true

    try {
      const res = await askAi(question)
      messages.value.push({
        role: 'assistant',
        content: res.answer,
        sources: res.sources,
        timestamp: Date.now(),
      })
      let quotaMsg: string | undefined
      if (res.remainingQuota >= 0) {
        quotaMsg = `今日剩余免费次数: ${res.remainingQuota}(会员不限次)`
      }
      return { quotaMsg }
    } catch (e: any) {
      messages.value.push({
        role: 'assistant',
        content: e.code === 429 ? e.message : `出错了: ${e.message}`,
        timestamp: Date.now(),
      })
      return {}
    } finally {
      loading.value = false
    }
  }

  function clearMessages() {
    messages.value = [
      { role: 'assistant', content: '你好!我是科技树 AI 向导。试试问我:「蒸汽机的前置技术有哪些?」' }
    ]
  }

  return { messages, loading, ask, clearMessages }
}
