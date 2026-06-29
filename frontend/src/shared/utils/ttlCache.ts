/**
 * 轻量内存 TTL 缓存:命中且未过期则直接返回,否则执行 loader 并缓存结果。
 *
 * 用于"读多写少、页面切换时会重复拉取"的数据(图谱主树、产业链列表等)。
 * 与 keep-alive 互补:keep-alive 让组件实例不销毁(切 tab 不重拉),
 * 这里再补一层数据缓存,覆盖首次加载、组件被挤出缓存后的重建等场景。
 */
interface CacheEntry<T> {
  value: T
  expireAt: number
}

const store = new Map<string, CacheEntry<unknown>>()

/**
 * 带过期时间的缓存读取。
 * @param key    缓存键(建议含接口名+参数)
 * @param ttlMs  过期毫秒数
 * @param loader 未命中或已过期时的加载函数
 */
export function ttlCache<T>(key: string, ttlMs: number, loader: () => Promise<T>): Promise<T> {
  const hit = store.get(key) as CacheEntry<T> | undefined
  if (hit && hit.expireAt > Date.now()) {
    return Promise.resolve(hit.value)
  }
  return loader().then(value => {
    store.set(key, { value, expireAt: Date.now() + ttlMs })
    return value
  })
}

/**
 * 主动失效。不传 prefix 清空全部;传 prefix 清除以该前缀开头的键。
 * 写操作(如管理员改图谱)后调用,避免用户看到过期数据。
 */
export function invalidateTtlCache(prefix?: string): void {
  if (!prefix) {
    store.clear()
    return
  }
  for (const key of store.keys()) {
    if (key.startsWith(prefix)) store.delete(key)
  }
}
