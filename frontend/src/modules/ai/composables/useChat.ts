import { ref } from 'vue'
import { streamAsk, type StreamMeta } from '../api/chat'
import type { ChatMessage } from '../types'

const WELCOME: ChatMessage = {
  role: 'assistant',
  content:
    '你好，我是科技树 AI 向导。选一个节点，我会结合上下文跟你聊它的前置知识、为什么重要、接下来学什么；也可以直接问我任何技术问题。',
  mode: 'guide',
  format: 'markdown:v1',
}

export function useChat() {
  const messages = ref<ChatMessage[]>([{ ...WELCOME }])
  const loading = ref(false)
  const phase = ref('')
  const quotaMsg = ref<string | undefined>(undefined)

  async function ask(question: string, isLoggedIn: boolean): Promise<{ quotaMsg?: string }> {
    const trimmed = question.trim()
    if (!trimmed) return {}

    // 未登录:不发起流式,直接给提示。
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

    // 用户消息入列。
    messages.value.push({ role: 'user', content: trimmed, timestamp: Date.now() })

    // 先占位一条空的 assistant 消息,流式中就地增量更新它的 content / thinking。
    const placeholder: ChatMessage = {
      role: 'assistant',
      content: '',
      thinking: '',
      streaming: true,
      timestamp: Date.now(),
    }
    messages.value.push(placeholder)

    loading.value = true
    phase.value = '正在检索图谱上下文'
    quotaMsg.value = undefined

    let seenDelta = false
    let seenThinking = false

    return new Promise<{ quotaMsg?: string }>(resolve => {
      const abort = streamAsk(trimmed, {
        onMeta: (meta: StreamMeta) => {
          // 来源/配额/步骤先到,就地回填占位消息。
          placeholder.sources = meta.sources
          placeholder.steps = meta.steps
          placeholder.intent = meta.intent
          placeholder.mode = meta.mode
          placeholder.format = 'markdown:v1'
          if (meta.remainingQuota >= 0) {
            quotaMsg.value = `今日剩余免费次数: ${meta.remainingQuota}（会员不限次）`
          }
        },
        onThinking: delta => {
          if (!seenThinking) {
            seenThinking = true
            phase.value = '正在思考'
          }
          placeholder.thinking = (placeholder.thinking ?? '') + delta
        },
        onDelta: delta => {
          if (!seenDelta) {
            seenDelta = true
            phase.value = '正在生成回答'
          }
          placeholder.content += delta
        },
        onDone: (_mode, _format) => {
          placeholder.streaming = false
          // 空回答兜底,避免渲染空白气泡。
          if (!placeholder.content.trim()) {
            placeholder.content = '资料不足以生成可靠回答。'
          }
          loading.value = false
          phase.value = ''
          resolve({ quotaMsg: quotaMsg.value })
        },
        onError: message => {
          placeholder.streaming = false
          placeholder.mode = 'error'
          placeholder.format = 'markdown:v1'
          placeholder.content = `### 结论\n${message}\n\n### 下一步\n- 请稍后重试，或换一个更具体的问题。`
          loading.value = false
          phase.value = ''
          resolve({})
        },
      })
      // 把 abort 挂到占位消息上,便于组件卸载/切换时中断(可选,此处仅持有不强制)。
      ;(placeholder as ChatMessage & { _abort?: () => void })._abort = abort
    })
  }

  function clearMessages() {
    messages.value = [{ ...WELCOME }]
  }

  return { messages, loading, phase, ask, clearMessages }
}
