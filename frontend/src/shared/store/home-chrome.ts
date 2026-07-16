import { inject, type InjectionKey, type Ref } from 'vue'

/**
 * 首页壳层(HomeShell)下发的业务弹窗控制器。
 *
 * LoginModal / MemberModal / BindEmailModal / SetPasswordModal 是跨业务模块的
 * 全屏弹窗,被首页装配。为避免首页 view(graph 的 HomeView)直接 import 其他
 * 业务模块的组件,这些弹窗由 app 层 HomeShell 持有并装配,业务层通过
 * useHomeChrome() inject 这个控制器触发开关。
 *
 * 设计权衡:开关状态用 Ref 暴露(业务层既可调 openXxx(),也可直接读 showXxx.value)。
 * SetPassword 的 done/skip 结果由 HomeShell 通过事件 emit 给业务层处理
 * (刷新登录态等),不放进控制器以保持控制器职责单一。
 */
export interface HomeChrome {
  readonly showLogin: Ref<boolean>
  readonly showMember: Ref<boolean>
  readonly showBindEmail: Ref<boolean>
  readonly showSetPassword: Ref<boolean>
  openLogin: () => void
  openMember: () => void
  openBindEmail: () => void
  openSetPassword: () => void
  /** AiChatPanel 侧栏的图谱上下文(由业务层设置)。 */
  readonly aiContextNode: Ref<{ id: number; name: string } | null>
  /** SetPassword 弹窗的 done/skip 回调(由业务层注册)。 */
  onSetPasswordDone: (() => void) | null
  onSetPasswordSkip: (() => void) | null
}

export const HOME_CHROME_KEY: InjectionKey<HomeChrome> = Symbol('home-chrome')

export function useHomeChrome(): HomeChrome {
  const chrome = inject(HOME_CHROME_KEY)
  if (!chrome) {
    throw new Error('useHomeChrome() called outside of HomeShell; wrap the home route in <HomeShell>.')
  }
  return chrome
}
