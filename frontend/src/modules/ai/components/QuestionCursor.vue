<template>
  <nav class="question-cursor" aria-label="提问游标">
    <button
      v-for="(item, index) in items"
      :key="item.id"
      class="cursor-mark"
      :class="{ active: index === activeIndex }"
      type="button"
      :title="item.label"
      :aria-label="`跳到第 ${index + 1} 个问题`"
      :aria-current="index === activeIndex ? 'true' : undefined"
      @click="$emit('select', index)"
    >
      <span :style="{ width: `${markWidth(index)}px` }" />
    </button>
  </nav>
</template>

<script setup lang="ts">
defineProps<{
  items: Array<{ id: string | number; label: string }>
  activeIndex: number
}>()

defineEmits<{
  select: [index: number]
}>()

function markWidth(index: number) {
  const widths = [24, 18, 14, 10, 8]
  return widths[Math.min(index, widths.length - 1)]
}
</script>

<style scoped>
.question-cursor {
  flex: none;
  width: 32px;
  padding: 14px 4px 10px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  background: #151515;
}

.cursor-mark {
  width: 24px;
  height: 7px;
  display: grid;
  place-items: center start;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.cursor-mark span {
  display: block;
  height: 2px;
  border-radius: 999px;
  background: #5f5f5f;
  transition: width 0.16s ease, background 0.16s ease, opacity 0.16s ease;
}

.cursor-mark:hover span {
  background: #b8b8b8;
}

.cursor-mark.active span {
  width: 24px !important;
  height: 3px;
  background: #ffffff;
}

@media (max-width: 640px) {
  .question-cursor {
    width: 26px;
  }
}
</style>
