import { computed, ref } from 'vue'
import { versionApi } from '@/api/system'
import type { VersionInfo } from '@/types/system'

/**
 * 后端的版本号：登录页底部、用户菜单里显示"版本 x"，有人报问题时知道他用的是哪一版。
 * 整个页面只取一次；没取到（比如后端正在重启）下次用到时再取
 */
const info = ref<VersionInfo | null>(null)
let pending: Promise<void> | null = null

export function useVersion() {
  if (!info.value && !pending) {
    pending = versionApi
      .get()
      .then((result) => {
        info.value = result
      })
      .catch(() => {
        // 不显示版本号就是了
      })
      .finally(() => {
        pending = null
      })
  }
  return {
    version: computed(() => info.value?.version ?? ''),
    /** 鼠标停在版本号上显示打包时间 */
    title: computed(() => (info.value?.buildTime ? `打包时间 ${info.value.buildTime}` : undefined)),
  }
}
