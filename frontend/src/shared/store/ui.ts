import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 图谱视图模式:地图 / 对话。仅 HomeView 使用,但 AppHeader 需要据此高亮导航。 */
export type GraphMode = 'map' | 'dialog'

/**
 * 全局 UI 状态:连接 App.vue 的共享布局壳与 HomeView 的页面私有状态。
 *
 * <p>设计:store 只持有 AppHeader 需要读取/写入的"轻量视图状态"。图谱渲染
 * 相关的重操作(chart resize、退出对话模式等)仍由 HomeView 通过 watch 触发,
 * 不下沉到 store —— 这与 chat store 只存列表、不存高频消息的取舍一致。</p>
 *
 * <p>状态生命周期:graphMode/graphFullScreen 默认值即首页初始态;
 * aiRailCollapsed 与 HomeView 原有行为一致地持久化到 localStorage。</p>
 */
export const useUiStore = defineStore('ui', () => {
  /** 当前图谱视图模式。 */
  const graphMode = ref<GraphMode>('map')
  /** 是否进入全屏沉浸模式(全屏时隐藏 AI 栏与侧栏)。 */
  const graphFullScreen = ref(false)
  /** 右侧常驻 AI 对话栏是否折叠;与全屏互斥。持久化在 localStorage。 */
  const aiRailCollapsed = ref(localStorage.getItem('sparrow_ai_rail_collapsed') === '1')

  function toggleAiRail() {
    aiRailCollapsed.value = !aiRailCollapsed.value
    localStorage.setItem('sparrow_ai_rail_collapsed', aiRailCollapsed.value ? '1' : '0')
  }

  function setGraphFullScreen(value: boolean) {
    graphFullScreen.value = value
  }

  return { graphMode, graphFullScreen, aiRailCollapsed, toggleAiRail, setGraphFullScreen }
})
