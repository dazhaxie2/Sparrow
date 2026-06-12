import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatSession } from '../../types/ai'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const activeSessionId = ref<string | null>(null)

  function createSession(title: string = '新对话'): ChatSession {
    const session: ChatSession = {
      id: Date.now().toString(),
      title,
      messages: [],
      createdAt: Date.now(),
    }
    sessions.value.unshift(session)
    activeSessionId.value = session.id
    return session
  }

  function getActiveSession(): ChatSession | undefined {
    return sessions.value.find(s => s.id === activeSessionId.value)
  }

  return { sessions, activeSessionId, createSession, getActiveSession }
})
