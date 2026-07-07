<template>
  <nav class="question-cursor" aria-label="提问游标">
    <button
      v-for="(item, index) in items"
      :key="item.id"
      class="cursor-mark"
      :class="{ active: index === activeIndex }"
      type="button"
      :aria-label="`跳到第 ${index + 1} 个问题`"
      :aria-current="index === activeIndex ? 'true' : undefined"
      @click="$emit('select', index)"
    >
      <i class="dot" />
      <span class="cursor-tip">{{ item.label }}</span>
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
</script>

<style scoped>
/* 千问风格：圆点列融进消息区背景，整组垂直居中，低调不抢焦。 */
.question-cursor {
  flex: none;
  width: 28px;
  padding: 10px 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center; /* 整列在容器高度内垂直居中 */
  gap: 7px;
}

.cursor-mark {
  position: relative; /* 给 .cursor-tip 气泡做定位锚 */
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

/* 圆点：默认极淡灰，融进背景。 */
.dot {
  display: block;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.16);
  transition: background 0.16s ease, width 0.16s ease, height 0.16s ease;
}

.cursor-mark:hover .dot {
  background: rgba(0, 0, 0, 0.4);
}

/* 当前提问：淡橙点缀，略大但不发光，保留品牌识别性。 */
.cursor-mark.active .dot {
  width: 6px;
  height: 6px;
  background: var(--accent);
}

/* 悬浮气泡：默认隐藏，hover 时淡入，显示问题缩略内容。 */
.cursor-tip {
  position: absolute;
  left: calc(100% + 8px);
  top: 50%;
  transform: translateY(-50%);
  z-index: 20;
  max-width: 320px;
  padding: 8px 11px;
  border-radius: 6px;
  background: var(--ink);
  color: #fff;
  font-size: 13.5px;
  line-height: 1.6;
  font-weight: 500;
  white-space: normal;
  /* 截断到四行，长问题也能看清主体内容 */
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.22);
  /* 避免气泡顶端贴着圆点边缘，留一点呼吸感 */
  word-break: break-word;
}

.cursor-mark:hover .cursor-tip {
  opacity: 1;
}

@media (max-width: 640px) {
  .question-cursor {
    width: 22px;
  }
}
</style>
