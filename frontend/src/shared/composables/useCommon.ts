import { ref } from 'vue'

export function useToast() {
  const visible = ref(false)
  const text = ref('')
  let timer: ReturnType<typeof setTimeout> | null = null

  function show(msg: string, duration = 2600) {
    text.value = msg
    visible.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { visible.value = false }, duration)
  }

  return { visible, text, show }
}
