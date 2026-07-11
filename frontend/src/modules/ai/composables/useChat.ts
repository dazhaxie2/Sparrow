import { ref } from 'vue'
import { streamAsk, type MessageItem, type StreamMeta } from '../api/chat'
import { useChatStore } from '../store/chat'
import type { AiHarnessMetadata, ChatMessage } from '../types'

const WELCOME: ChatMessage = {
  role: 'assistant',
  content:
    '你好，我是科技图 AI 向导。选一个节点，我会结合上下文跟你聊它的前置知识、为什么重要、接下来学什么；也可以直接问我任何技术问题。',
  mode: 'guide',
  format: 'markdown:v1',
}

export function useChat() {
  const store = useChatStore()
  const messages = ref<ChatMessage[]>([{ ...WELCOME }])
  const loading = ref(false)
  const phase = ref('')
  const quotaMsg = ref<string | undefined>(undefined)

  /**
   * 加载指定会话的历史消息(切换会话时调用)。
   * 失败或无消息时回到欢迎页;成功时用历史消息替换当前列表。
   */
  async function loadHistory(sessionId: number) {
    const history = await store.openSession(sessionId)
    if (history === null) {
      // 加载失败:不静默跳回欢迎页,而是给出可见提示,让用户知道是"加载失败"而非"会话为空"。
      // 保留激活态以便用户重试(再次点击该会话)。
      messages.value = [
        { ...WELCOME },
        {
          role: 'assistant',
          content: '### 结论\n加载该对话失败。\n\n### 下一步\n- 请稍后重试，或在历史列表中重新选择。',
          mode: 'error',
          format: 'markdown:v1',
          timestamp: Date.now(),
        },
      ]
      return
    }
    if (history.length === 0) {
      messages.value = [{ ...WELCOME }]
      return
    }
    // 历史消息转 ChatMessage 渲染(均为已完成态,无 streaming/thinking)
    messages.value = history.map((m: MessageItem) => ({
      role: m.role as ChatMessage['role'],
      content: m.content,
      mode: m.mode ?? undefined,
      format: 'markdown:v1',
      id: m.id,
      timestamp: new Date(m.createdAt).getTime(),
    }))
  }

  async function ask(
    question: string,
    isLoggedIn: boolean,
    surface = 'assistant-rail',
  ): Promise<{ quotaMsg?: string }> {
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

    // 确保存在激活会话(首次提问自动创建),拿到 sessionId 供后端落库。
    // 创建失败(未登录/网络)时 sessionId=null,问答正常进行但不落库。
    const sessionId = await store.ensureSession(trimmed)

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
    // 已为本次"降级补充"插入过分隔线则置真,避免多次 reset 叠加多条分隔。
    let resetSeparatorAdded = false

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
      // 统一收尾:保证 streaming=false、loading=false、phase 清空、残余 token 刷出、watchdog 清除。
      // onDone / onError / watchdog 三条收尾路径共用,避免任一处漏清状态导致 UI 卡死。
      // finishReason: 'complete'=收到业务 done,内容完整; 'interrupted'=连接断/超时/兜底收尾,内容可能不全。
      let settled = false
      let finishReason: 'complete' | 'interrupted' = 'interrupted'
      const settle = (onError?: string) => {
        if (settled) return
        settled = true
        if (watchdog) clearTimeout(watchdog)
        flush()
        reactivePlaceholder.streaming = false
        const hasContent = reactivePlaceholder.content.trim().length > 0
        if (onError) {
          reactivePlaceholder.mode = 'error'
          reactivePlaceholder.format = 'markdown:v1'
          if (hasContent) {
            // 已有部分内容时:不覆盖,保留已生成内容,末尾追加中断提示(避免突兀截断)。
            // 用 markdown 引用块弱化提示语气,与正文区分但不喧宾夺主。
            reactivePlaceholder.content += `\n\n> ⚠️ ${onError}（以上内容可能不完整）`
          } else {
            reactivePlaceholder.content = `### 结论\n${onError}\n\n### 下一步\n- 请稍后重试，或换一个更具体的问题。`
          }
        } else if (finishReason === 'interrupted' && hasContent) {
          // 传输层 onDone 兜底(连接关闭但无业务 done):内容可能被截断,追加温和提示。
          reactivePlaceholder.content += '\n\n> ⚠️ 回答被中断，以上内容可能不完整，可重新提问继续。'
        } else if (!hasContent) {
          // 空回答兜底,避免渲染空白气泡。
          reactivePlaceholder.content = '资料不足以生成可靠回答。'
        }
        loading.value = false
        phase.value = ''
        resolve(onError ? {} : { quotaMsg: quotaMsg.value })
      }

      // 超时兜底:略大于后端 SseEmitter 120s 超时。若到此 loading 仍为 true
      // (后端漏发 done/error,或网络中断未触发传输层 onDone),强制收尾,防止永久转圈。
      let watchdog: ReturnType<typeof setTimeout> | undefined = setTimeout(() => {
        if (loading.value) settle('回答超时,请稍后重试。')
      }, 130_000)

      const abort = streamAsk(trimmed, {
        onHarness: (harness: AiHarnessMetadata) => {
          reactivePlaceholder.harness = harness
          phase.value = harnessPhase(harness)
        },
        onMeta: (meta: StreamMeta) => {
          // 来源/配额/步骤先到,就地回填占位消息。
          reactivePlaceholder.sources = meta.sources
          reactivePlaceholder.steps = meta.steps
          reactivePlaceholder.intent = meta.intent
          reactivePlaceholder.mode = meta.mode
          reactivePlaceholder.format = 'markdown:v1'
          reactivePlaceholder.harness = meta.harness
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
        onReset: () => {
          // 后端 Agent/RAG 超时或出错后会发 reset 并降级到下一级引擎。
          // 旧实现无条件清空 content,会把已经流给用户、正在阅读的回答整段抹掉,
          // 之后只追加下游降级产出的尾部,表现为"回答只剩最后一点点"。
          // 止血:先刷出残余 token,再判断是否已有实质正文——
          //   有正文就保留(降级产物改为"补充"而非覆盖);无正文才清空等下游重发。
          flush()
          const hasShownContent = reactivePlaceholder.content.trim().length > 0
          pendingDelta = ''
          pendingThinking = ''
          if (hasShownContent) {
            // 已显示的正文不动;用一个分隔让后续降级 delta 与已有回答区分,避免拼接成一句。
            if (!resetSeparatorAdded) {
              reactivePlaceholder.content += '\n\n---\n\n'
              resetSeparatorAdded = true
            }
          } else {
            reactivePlaceholder.content = ''
            reactivePlaceholder.thinking = ''
          }
          phase.value = '上游降级，正在补充回答'
        },
        onDone: (_mode, _format, harness) => {
          // 收到业务 done=正常完整收尾,标记 complete 让 settle 走"内容完整"路径。
          finishReason = 'complete'
          if (harness) reactivePlaceholder.harness = harness
          // 成功收尾:本会话消息数 +2(user+assistant),仅在有 session 时
          if (sessionId !== null) store.bumpMessageCount(sessionId, 2)
          settle()
        },
        onError: streamError => {
          if (streamError.harness) reactivePlaceholder.harness = streamError.harness
          const trace = streamError.traceId ? `（追踪 ID: ${streamError.traceId}）` : ''
          const retry = streamError.retryable ? '，可以重试' : ''
          settle(`${streamError.message}${trace}${retry}`)
        },
      }, sessionId, surface)
      // 把 abort 挂到占位消息上,便于组件卸载/切换时中断(可选,此处仅持有不强制)。
      ;(reactivePlaceholder as ChatMessage & { _abort?: () => void })._abort = abort
    })
  }

  /** 新建对话:清空消息回到欢迎页 + 重置激活会话(下次提问建新会话)。 */
  function clearMessages() {
    store.startNewSession()
    messages.value = [{ ...WELCOME }]
  }

  return { messages, loading, phase, ask, clearMessages, loadHistory, store }
}

const harnessStageLabels: Record<string, string> = {
  received: '请求已进入 Harness',
  policy: '正在执行输入与安全策略',
  context: '正在装配持久化会话上下文',
  retrieval: '正在检索图谱与知识库',
  execution: '正在调用模型与工具',
  recovery: '上游失败，正在切换恢复路径',
  validation: '正在校验回答完整性',
  persistence: '正在保存完整问答轮次',
  completed: '回答已完成并保存',
  failed: 'Harness 执行失败',
}

function harnessPhase(harness: AiHarnessMetadata) {
  return harnessStageLabels[harness.currentStage] ?? '正在处理'
}
