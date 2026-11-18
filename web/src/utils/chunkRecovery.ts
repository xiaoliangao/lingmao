import { readStorage, writeStorage } from './safeStorage.ts'
const KEY = 'lingmao-chunk-recovery'
/** 每个页签会话只自动刷新一次；再失败就留着能用的更新提示条。 */
export function claimChunkReload(): boolean {
  if (readStorage(KEY, true) !== null) return false
  return writeStorage(KEY, 'attempted', true)
}
export function isChunkError(error: unknown): boolean {
  return error instanceof Error && /ChunkLoadError|Loading chunk|Failed to fetch dynamically imported module|Importing a module script failed/.test(error.message)
}
export function installChunkRecovery(options: {
  onUpdate: () => void
  hasUnsavedChanges: () => boolean
  onRouterError: (handler: (error: unknown) => void) => () => void
}): () => void {
  const recover = (event?: Event) => {
    event?.preventDefault()
    options.onUpdate()
    if (!options.hasUnsavedChanges() && claimChunkReload()) location.reload()
  }
  window.addEventListener('vite:preloadError', recover)
  const stopRouter = options.onRouterError((error) => { if (isChunkError(error)) recover() })
  return () => { window.removeEventListener('vite:preloadError', recover); stopRouter() }
}
