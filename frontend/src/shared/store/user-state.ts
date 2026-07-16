import { inject, type InjectionKey } from 'vue'
import type { useUserStore } from '../../modules/user/store'

/**
 * 用户认证状态注入键。
 *
 * useUserStore 定义在 user 业务模块(领域归属正确),但它被多个业务模块 + app 层
 * 消费。为避免业务模块之间互相 import store 文件,由 app 层(App.vue)在根组件
 * 调 useUserStore() 后 provide,各业务模块通过 useUserState() inject 取用。
 *
 * 注入的是 store 实例本身(响应式),消费方可直接读 profile / 调 isLoggedIn()。
 */
export type UserStore = ReturnType<typeof useUserStore>

export const USER_STORE_KEY: InjectionKey<UserStore> = Symbol('user-store')

/**
 * 业务模块取用用户认证状态的合规入口(inject)。
 * 必须在 App.vue provide 后才能调用;未 provide 时抛错以便尽早发现装配遗漏。
 */
export function useUserState(): UserStore {
  const store = inject(USER_STORE_KEY)
  if (!store) {
    throw new Error('useUserState() called outside of provider; App.vue must provide(USER_STORE_KEY).')
  }
  return store
}
