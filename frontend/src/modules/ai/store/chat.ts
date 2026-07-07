import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as chatApi from '../api/chat'
import type { ChatSession } from '../types'

/**
 * 聊天历史 store:管理会话列表 + 当前激活会话。
 *
 * <p>设计:store 只持有"会话列表"和"激活会话 id"。具体的消息列表(messages)
 * 由 useChat composable 持有 —— 因为消息是流式高频更新的本地状态,
 * 放进全局 store 会带来不必要的响应式开销,且 useChat 已有完善的批处理逻辑。</p>
 *
 * <p>会话生命周期:
 * <ul>
 *   <li>未登录或初次进入:activeSessionId = null,提问时按需创建会话</li>
 *   <li>提问时(ensureSessionAndAsk):若无激活会话,先 POST 创建,拿到 id 后更新</li>
 *   <li>切换会话(openSession):从后端拉该会话消息,交给 useChat 渲染</li>
 *   <li>新建对话(startNewSession):清空 activeSessionId,回到无会话态</li>
 * </ul></p>
 */
export const useChatStore = defineStore('chat', () => {
  /** 会话列表(列表视图数据,不含消息体)。 */
  const sessions = ref<ChatSession[]>([])
  /** 当前激活会话 id;null 表示未选中任何会话(新建对话态)。 */
  const activeSessionId = ref<number | null>(null)
  /** 历史抽屉是否展开。 */
  const historyOpen = ref(false)
  /** 会话列表加载中。 */
  const sessionsLoading = ref(false)

  /** 拉取会话列表(登录后调用)。失败静默(未登录等场景)。 */
  async function loadSessions() {
    sessionsLoading.value = true
    try {
      const items = await chatApi.listSessions()
      sessions.value = items.map(item => ({
        id: item.id,
        title: item.title,
        createdAt: new Date(item.createdAt).getTime(),
        updatedAt: new Date(item.updatedAt).getTime(),
        messageCount: item.messageCount,
      }))
    } catch {
      // 未登录或网络错误:保持空列表,不阻塞 UI
      sessions.value = []
    } finally {
      sessionsLoading.value = false
    }
  }

  /**
   * 确保存在激活会话,返回其 id。若当前无激活会话,先创建一个(以首问截断为标题)。
   * 由 useChat.ask 在发起流式问答前调用。
   */
  async function ensureSession(question: string): Promise<number | null> {
    if (activeSessionId.value !== null) {
      return activeSessionId.value
    }
    try {
      const { sessionId } = await chatApi.createSession(question)
      activeSessionId.value = sessionId
      // 新会话插入列表头部(本地预占,待下次 loadSessions 同步)
      sessions.value.unshift({
        id: sessionId,
        title: deriveTitle(question),
        createdAt: Date.now(),
        updatedAt: Date.now(),
        messageCount: 0,
      })
      return sessionId
    } catch {
      // 创建失败(未登录/网络):返回 null,useChat 会以无会话态继续(不落库)
      return null
    }
  }

  /** 切换到指定会话。返回其消息列表(供 useChat 渲染)。失败返回 null。 */
  async function openSession(id: number) {
    try {
      const messages = await chatApi.getSessionMessages(id)
      activeSessionId.value = id
      return messages
    } catch {
      return null
    }
  }

  /** 仅设置激活会话 id,不拉取消息(供 UI 先切换高亮,消息由调用方异步加载)。 */
  function setActive(id: number | null) {
    activeSessionId.value = id
  }

  /** 新建对话:清空激活会话,回到无会话态(下次提问会创建新会话)。 */
  function startNewSession() {
    activeSessionId.value = null
  }

  /** 删除会话(级联删消息)。删除当前激活会话时,回到新建态。 */
  async function removeSession(id: number) {
    try {
      await chatApi.deleteSession(id)
      sessions.value = sessions.value.filter(s => s.id !== id)
      if (activeSessionId.value === id) {
        activeSessionId.value = null
      }
      return true
    } catch {
      return false
    }
  }

  /** 更新本地会话的 messageCount(提问后调用)。 */
  function bumpMessageCount(id: number, delta: number) {
    const s = sessions.value.find(x => x.id === id)
    if (s) {
      s.messageCount = (s.messageCount ?? 0) + delta
      s.updatedAt = Date.now()
    }
  }

  return {
    sessions,
    activeSessionId,
    historyOpen,
    sessionsLoading,
    loadSessions,
    ensureSession,
    openSession,
    setActive,
    startNewSession,
    removeSession,
    bumpMessageCount,
  }
})

/** 首问转标题:去"围绕「xxx」回答:"前缀,截断 20 字。与后端 buildTitle 对齐。 */
function deriveTitle(question: string): string {
  const text = question.trim().replace(/^围绕「[^」]*」回答[:：]\s*/, '')
  return text.length <= 20 ? text : text.slice(0, 20) + '…'
}
