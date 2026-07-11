<template>
  <Teleport to="body">
    <div v-if="showLearning" class="lc-mask" @mousedown="dismissLearning.onMaskMousedown" @mouseup="dismissLearning.onMaskMouseup">
      <div class="lc-modal">
        <header class="lc-head">
          <strong>我的学习</strong>
          <button type="button" @click="$emit('update:showLearning', false)"><X :size="16" /></button>
        </header>
        <div class="lc-body">
          <section v-for="key in (['want', 'read', 'mastered'] as const)" :key="key" class="lc-group">
            <h4>{{ LEARNING_LABELS[key] }}<small>{{ learningGroups[key].length }}</small></h4>
            <p v-if="!learningGroups[key].length" class="lc-empty">暂无</p>
            <div v-else class="lc-list">
              <div v-for="item in learningGroups[key]" :key="item.id" class="lc-item">
                <button type="button" class="lc-go" @click="$emit('openLearningNode', item.id)">
                  <strong>{{ item.name }}</strong>
                  <small>{{ item.era }}{{ item.yearLabel ? ' · ' + item.yearLabel : '' }}</small>
                </button>
                <button type="button" class="lc-rm" title="移出" @click="$emit('removeProgress', item.id)"><X :size="13" /></button>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="showSettings" class="lc-mask" @mousedown="dismissSettings.onMaskMousedown" @mouseup="dismissSettings.onMaskMouseup">
      <div class="lc-modal settings">
        <header class="lc-head">
          <strong>设置</strong>
          <button type="button" @click="$emit('update:showSettings', false)"><X :size="16" /></button>
        </header>
        <div class="lc-body">
          <label class="set-row">
            <span>图谱默认显示边标签</span>
            <input
              type="checkbox"
              :checked="showEdgeLabels"
              @change="$emit('update:showEdgeLabels', ($event.target as HTMLInputElement).checked)"
            />
          </label>
          <button type="button" class="set-clear" @click="$emit('clearAllProgress')">清空我的学习记录</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { X } from '@lucide/vue'
import { LEARNING_LABELS, type ProgressState } from '../composables/useLearningProgress'
import { useDismissableOverlay } from '../../../shared/composables/useDismissableOverlay'

type LearningGroups = Record<ProgressState, Array<{ id: number; name: string; era: string; yearLabel: string }>>

defineProps<{
  showLearning: boolean
  showSettings: boolean
  learningGroups: LearningGroups
  showEdgeLabels: boolean
}>()

const emit = defineEmits<{
  'update:showLearning': [value: boolean]
  'update:showSettings': [value: boolean]
  'update:showEdgeLabels': [value: boolean]
  openLearningNode: [id: number]
  removeProgress: [id: number]
  clearAllProgress: []
}>()

const dismissLearning = useDismissableOverlay(() => emit('update:showLearning', false))
const dismissSettings = useDismissableOverlay(() => emit('update:showSettings', false))
</script>

<style scoped>
.lc-mask {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(2px);
}

.lc-modal {
  width: min(480px, calc(100vw - 32px));
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--ink);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.lc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.lc-head strong {
  font-size: 16px;
}

.lc-head button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--ink-2);
  cursor: pointer;
}

.lc-head button:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.lc-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 18px;
}

.lc-group + .lc-group {
  margin-top: 18px;
}

.lc-group h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
  font-size: 13px;
  color: var(--ink);
}

.lc-group h4 small {
  color: var(--muted);
  font-weight: 400;
}

.lc-empty {
  color: var(--muted);
  font-size: 12px;
}

.lc-list {
  display: grid;
  gap: 6px;
}

.lc-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.lc-item:hover {
  border-color: var(--accent);
}

.lc-go {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
  border: 0;
  background: transparent;
  padding: 9px 11px;
  text-align: left;
  cursor: pointer;
}

.lc-go strong {
  overflow: hidden;
  font-size: 13px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lc-go small {
  font-size: 11px;
  color: var(--muted);
}

.lc-rm {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.lc-rm:hover {
  color: var(--danger);
}

.set-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  font-size: 13px;
  color: var(--ink);
}

.set-clear {
  margin-top: 14px;
  width: 100%;
  min-height: 36px;
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--danger);
  font-size: 13px;
  cursor: pointer;
}

.set-clear:hover {
  background: rgba(220, 38, 38, 0.06);
}
</style>
