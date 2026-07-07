import { ref } from 'vue'
import { streamAsk, type StreamMeta } from '../api/chat'
import type { ChatMessage } from '../types'

const WELCOME: ChatMessage = {
  role: 'assistant',
  content:
    '你好，我是科技图 AI 向导。选一个节点，我会结合上下文跟你聊它的前置知识、为什么重要、接下来学什么；也可以直接问我任何技术问题。',
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
    // ⚠ 必须用 push 后从响应式数组取回的代理对象,不能保留裸对象引用:
    // Vue3 对数组的包装只代理"被访问到的元素",本地裸 placeholder 指向原始对象,
    // 直接 mutate 它不会触发响应式更新(曾导致流式 token 不刷新)。
    const placeholder: ChatMessage = {
      role: 'assistant',
      content: '',
      thinking: '',
      streaming: true,
      timestamp: Date.now(),
    }
    messages.value.push(placeholder)
    // 拿到 Vue 代理后的引用,后续所有就地更新都用这个。
    const reactivePlaceholder = messages.value[messages.value.length - 1]

    loading.value = true
    phase.value = '正在检索图谱上下文'
    quotaMsg.value = undefined

    let seenDelta = false
    let seenThinking = false

    // delta/thinking 批处理:同一帧(浏览器刷新率 ~60fps)内的多个 token 合并为一次 reactive 更新,
    // 避免 langchain4j 高频吐 token 时每个 token 都触发一次全量 markdown 重渲染 + DOM 重建(O(n²))。
    let pendingDelta = ''
    let pendingThinking = ''
    let flushScheduled = false
    const flush = () => {
      flushScheduled = false
      if (pendingDelta) {
        reactivePlaceholder.content += pendingDelta
        pendingDelta = ''
      }
      if (pendingThinking) {
        reactivePlaceholder.thinking = (reactivePlaceholder.thinking ?? '') + pendingThinking
        pendingThinking = ''
      }
    }
    const scheduleFlush = () => {
      if (!flushScheduled) {
        flushScheduled = true
        // requestAnimationFrame 在隐藏标签页会被节流到 ~1fps;此时回退到 setTimeout 保底刷新。
        if (typeof requestAnimationFrame !== 'undefined' && !document.hidden) {
          requestAnimationFrame(flush)
        } else {
          setTimeout(flush, 100)
        }
      }
    }

    return new Promise<{ quotaMsg?: string }>(resolve => {
      const abort = streamAsk(trimmed, {
        onMeta: (meta: StreamMeta) => {
          // 来源/配额/步骤先到,就地回填占位消息。
          reactivePlaceholder.sources = meta.sources
          reactivePlaceholder.steps = meta.steps
          reactivePlaceholder.intent = meta.intent
          reactivePlaceholder.mode = meta.mode
          reactivePlaceholder.format = 'markdown:v1'
          if (meta.remainingQuota >= 0) {
            quotaMsg.value = `今日剩余免费次数: ${meta.remainingQuota}（会员不限次）`
          }
        },
        onThinking: delta => {
          if (!seenThinking) {
            seenThinking = true
            phase.value = '正在思考'
          }
          pendingThinking += delta
          scheduleFlush()
        },
        onDelta: delta => {
          if (!seenDelta) {
            seenDelta = true
            phase.value = '正在生成回答'
          }
          pendingDelta += delta
          scheduleFlush()
        },
        onDone: (_mode, _format) => {
          // 收尾前把缓冲区残余 token 全部刷出,保证最终内容完整。
          flush()
          reactivePlaceholder.streaming = false
          // 空回答兜底,避免渲染空白气泡。
          if (!reactivePlaceholder.content.trim()) {
            reactivePlaceholder.content = '资料不足以生成可靠回答。'
          }
          loading.value = false
          phase.value = ''
          resolve({ quotaMsg: quotaMsg.value })
        },
        onError: message => {
          flush()
          reactivePlaceholder.streaming = false
          reactivePlaceholder.mode = 'error'
          reactivePlaceholder.format = 'markdown:v1'
          reactivePlaceholder.content = `### 结论\n${message}\n\n### 下一步\n- 请稍后重试，或换一个更具体的问题。`
          loading.value = false
          phase.value = ''
          resolve({})
        },
      })
      // 把 abort 挂到占位消息上,便于组件卸载/切换时中断(可选,此处仅持有不强制)。
      ;(reactivePlaceholder as ChatMessage & { _abort?: () => void })._abort = abort
    })
  }

  function clearMessages() {
    messages.value = [{ ...WELCOME }]
  }

  return { messages, loading, phase, ask, clearMessages }
}
