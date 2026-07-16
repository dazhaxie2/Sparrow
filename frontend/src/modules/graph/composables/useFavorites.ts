import { computed, ref } from 'vue'
import {
  createFavoriteFolder,
  deleteFavoriteFolder,
  favoriteNode,
  fetchFavoriteDetails,
  fetchFavoriteFolders,
  moveFavoriteNode,
  renameFavoriteFolder,
  unfavoriteNode,
  type FavoriteFolder,
  type FavoriteFolderDetail,
} from '../api'

/** 一句话：用户收藏夹与节点收藏的状态管理，登录用户走后端，未登录视为空。 */
export function useFavorites() {
  const folders = ref<FavoriteFolder[]>([])
  const folderDetails = ref<FavoriteFolderDetail[]>([])
  const loading = ref(false)
  const error = ref('')

  function folderById(id: number) {
    return folders.value.find(f => f.id === id)
  }

  const folderIdByNodeId = computed(() => {
    const map = new Map<number, number>()
    for (const folder of folderDetails.value) {
      for (const node of folder.nodes) {
        map.set(node.id, folder.id)
      }
    }
    return map
  })

  const favoriteCounts = computed(() => {
    const counts: Record<number, number> = {}
    for (const folder of folderDetails.value) {
      counts[folder.id] = folder.nodes.length
    }
    return counts
  })

  const totalFavorites = computed(() =>
    folderDetails.value.reduce((sum, f) => sum + f.nodes.length, 0),
  )

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const [list, details] = await Promise.all([
        fetchFavoriteFolders(),
        fetchFavoriteDetails(),
      ])
      folders.value = list
      folderDetails.value = details
    } catch (err: any) {
      error.value = err.message || '加载收藏失败'
    } finally {
      loading.value = false
    }
  }

  async function addFolder(name: string) {
    const folder = await createFavoriteFolder(name)
    folders.value = [...folders.value, folder]
    folderDetails.value = [...folderDetails.value, { ...folder, nodes: [] }]
    return folder
  }

  async function renameFolder(id: number, name: string) {
    const updated = await renameFavoriteFolder(id, name)
    folders.value = folders.value.map(f => (f.id === id ? updated : f))
    folderDetails.value = folderDetails.value.map(f =>
      f.id === id ? { ...f, name: updated.name } : f,
    )
  }

  async function removeFolder(id: number) {
    await deleteFavoriteFolder(id)
    folders.value = folders.value.filter(f => f.id !== id)
    folderDetails.value = folderDetails.value.filter(f => f.id !== id)
  }

  async function addNode(folderId: number, nodeId: number, node?: { id: number; name: string; era: string; yearLabel: string }) {
    await favoriteNode(folderId, nodeId)
    folderDetails.value = folderDetails.value.map(f => {
      if (f.id === folderId) {
        const nodes = f.nodes.filter(n => n.id !== nodeId)
        nodes.push(node ?? { id: nodeId, name: `节点 #${nodeId}`, era: '', yearLabel: '' })
        return { ...f, nodes }
      }
      // 同一节点只能在一个收藏夹：从其它收藏夹移除
      if (f.nodes.some(n => n.id === nodeId)) {
        return { ...f, nodes: f.nodes.filter(n => n.id !== nodeId) }
      }
      return f
    })
  }

  async function moveNode(nodeId: number, targetFolderId: number) {
    await moveFavoriteNode(nodeId, targetFolderId)
    const movingNode = folderDetails.value.flatMap(f => f.nodes).find(n => n.id === nodeId)
    folderDetails.value = folderDetails.value.map(f => {
      if (f.id === targetFolderId) {
        const nodes = f.nodes.filter(n => n.id !== nodeId)
        if (movingNode) nodes.push(movingNode)
        return { ...f, nodes }
      }
      return { ...f, nodes: f.nodes.filter(n => n.id !== nodeId) }
    })
  }

  async function removeNode(nodeId: number) {
    await unfavoriteNode(nodeId)
    folderDetails.value = folderDetails.value.map(f => ({
      ...f,
      nodes: f.nodes.filter(n => n.id !== nodeId),
    }))
  }

  function folderNameOf(nodeId: number) {
    const folderId = folderIdByNodeId.value.get(nodeId)
    if (folderId == null) return null
    return folderById(folderId)?.name ?? null
  }

  return {
    folders,
    folderDetails,
    folderIdByNodeId,
    favoriteCounts,
    totalFavorites,
    loading,
    error,
    load,
    addFolder,
    renameFolder,
    removeFolder,
    addNode,
    moveNode,
    removeNode,
    folderNameOf,
  }
}
