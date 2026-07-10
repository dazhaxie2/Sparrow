import { ref } from 'vue'

export type ToastKind = 'ok' | 'err'

export function useToast() {
  const visible = ref(false)
  const text = ref('')
  const kind = ref<ToastKind>('ok')
  let timer: ReturnType<typeof setTimeout> | null = null

  /** 显示一条 toast。kind=ok 默认成功样式,err 为错误样式。 */
  function show(msg: string, toastKind: ToastKind = 'ok', duration = 2600) {
    text.value = msg
    kind.value = toastKind
    visible.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { visible.value = false }, duration)
  }

  return { visible, text, kind, show }
}
