<template>
  <Teleport to="body">
    <div v-if="showLearning" class="sparrow-overlay" @mousedown="dismissLearning.onMaskMousedown" @mouseup="dismissLearning.onMaskMouseup">
      <div class="sparrow-modal fav-modal">
        <header class="fav-head">
          <strong>我的收藏</strong>
          <button type="button" @click="$emit('update:showLearning', false)"><X :size="16" /></button>
        </header>
        <div class="fav-body">
          <div v-if="!folderDetails.length" class="fav-empty">
            <p>还没有收藏任何节点。</p>
            <p>在节点详情页点击「收藏」即可加入。</p>
          </div>
          <section v-for="folder in folderDetails" :key="folder.id" class="fav-group">
            <div class="fav-group-head">
              <h4>
                <Folder :size="13" />
                <span v-if="editingFolderId !== folder.id">{{ folder.name }}</span>
                <input v-else v-model="editingName" type="text" maxlength="64" @keydown.enter.prevent="saveRename(folder.id)" @blur="saveRename(folder.id)" />
                <small>{{ folder.nodeIds.length }}</small>
              </h4>
              <div class="fav-actions">
                <button type="button" title="重命名" @click="startRename(folder.id, folder.name)"><Pencil :size="12" /></button>
                <button type="button" title="删除" :disabled="folderDetails.length === 1" @click="$emit('removeFolder', folder.id)"><Trash2 :size="12" /></button>
              </div>
            </div>
            <p v-if="!folder.nodes.length" class="fav-empty">暂无</p>
            <div v-else class="fav-list">
              <div v-for="node in folder.nodes" :key="`${folder.id}-${node.id}`" class="fav-item">
                <button type="button" class="fav-go" @click="$emit('openFavoriteNode', node.id)">
                  <strong>{{ node.name }}</strong>
                  <small>{{ node.era }}{{ node.yearLabel ? ' · ' + node.yearLabel : '' }}</small>
                </button>
                <select
                  class="fav-move"
                  title="移动到"
                  @change="moveNode(node.id, Number(($event.target as HTMLSelectElement).value))"
                >
                  <option value="" selected disabled>移动到</option>
                  <option v-for="f in folderOptions(folder.id)" :key="f.id" :value="f.id">{{ f.name }}</option>
                </select>
                <button type="button" class="fav-rm" title="取消收藏" @click="$emit('removeFavoriteNode', node.id)"><X :size="13" /></button>
              </div>
            </div>
          </section>
          <div class="fav-add-folder">
            <input v-model="newFolderName" type="text" placeholder="新建收藏夹" maxlength="64" @keydown.enter.prevent="createFolder" />
            <button type="button" :disabled="!newFolderName.trim()" @click="createFolder"><Plus :size="13" /> 新建</button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="showSettings" class="sparrow-overlay" @mousedown="dismissSettings.onMaskMousedown" @mouseup="dismissSettings.onMaskMouseup">
      <div class="sparrow-modal fav-modal settings">
        <header class="fav-head">
          <strong>设置</strong>
          <button type="button" @click="$emit('update:showSettings', false)"><X :size="16" /></button>
        </header>
        <div class="fav-body">
          <label class="set-row">
            <span>图谱默认显示边标签</span>
            <input
              type="checkbox"
              :checked="showEdgeLabels"
              @change="$emit('update:showEdgeLabels', ($event.target as HTMLInputElement).checked)"
            />
          </label>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Folder, Pencil, Plus, Trash2, X } from '@lucide/vue'
import { useDismissableOverlay } from '../../../shared/composables/useDismissableOverlay'
import type { FavoriteFolderDetail } from '../api'

const props = defineProps<{
  showLearning: boolean
  showSettings: boolean
  folders: { id: number; name: string; sortOrder: number }[]
  folderDetails: FavoriteFolderDetail[]
  showEdgeLabels: boolean
}>()

const emit = defineEmits<{
  'update:showLearning': [value: boolean]
  'update:showSettings': [value: boolean]
  'update:showEdgeLabels': [value: boolean]
  openFavoriteNode: [id: number]
  moveFavoriteNode: [nodeId: number, targetFolderId: number]
  removeFavoriteNode: [id: number]
  addFolder: [name: string]
  renameFolder: [id: number, name: string]
  removeFolder: [id: number]
}>()

const dismissLearning = useDismissableOverlay(() => emit('update:showLearning', false))
const dismissSettings = useDismissableOverlay(() => emit('update:showSettings', false))

const newFolderName = ref('')
const editingFolderId = ref<number | null>(null)
const editingName = ref('')

function folderOptions(currentFolderId: number) {
  return props.folders.filter(f => f.id !== currentFolderId)
}

function moveNode(nodeId: number, targetFolderId: number) {
  if (!targetFolderId) return
  emit('moveFavoriteNode', nodeId, targetFolderId)
}

function createFolder() {
  const name = newFolderName.value.trim()
  if (!name) return
  emit('addFolder', name)
  newFolderName.value = ''
}

function startRename(id: number, name: string) {
  editingFolderId.value = id
  editingName.value = name
}

function saveRename(id: number) {
  if (editingFolderId.value !== id) return
  const name = editingName.value.trim()
  editingFolderId.value = null
  if (!name) return
  emit('renameFolder', id, name)
}
</script>

<style scoped>
.fav-modal {
  width: min(520px, calc(100vw - 32px));
  max-height: 80vh;
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.fav-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.fav-head strong {
  font-size: 16px;
}

.fav-head button {
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

.fav-head button:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.fav-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 18px;
}

.fav-empty {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}

.fav-group + .fav-group {
  margin-top: 16px;
}

.fav-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.fav-group-head h4 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 13px;
  color: var(--ink);
}

.fav-group-head h4 svg {
  color: var(--accent);
}

.fav-group-head h4 small {
  color: var(--muted);
  font-weight: 400;
}

.fav-group-head h4 input {
  width: 140px;
  height: 24px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  padding: 0 6px;
  font-size: 12px;
}

.fav-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.fav-actions button {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.fav-actions button:hover:not(:disabled) {
  background: var(--surface-2);
  color: var(--ink);
}

.fav-actions button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.fav-list {
  display: grid;
  gap: 6px;
}

.fav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.fav-item:hover {
  border-color: var(--accent);
}

.fav-go {
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

.fav-go strong {
  overflow: hidden;
  font-size: 13px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fav-go small {
  font-size: 11px;
  color: var(--muted);
}

.fav-move {
  height: 26px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  background: var(--panel);
  color: var(--ink-2);
  font-size: 11px;
  cursor: pointer;
}

.fav-rm {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.fav-rm:hover {
  color: var(--danger);
}

.fav-add-folder {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.fav-add-folder input {
  flex: 1;
  height: 32px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 0 10px;
  font-size: 12px;
}

.fav-add-folder button {
  flex: none;
  height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--ink);
  padding: 0 10px;
  font-size: 12px;
  cursor: pointer;
}

.fav-add-folder button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.fav-add-folder button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.set-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  font-size: 13px;
  color: var(--ink);
}
</style>
