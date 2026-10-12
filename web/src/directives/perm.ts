import type { Directive } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * v-perm="'system:user:add'"：没有这个权限就把元素从页面上拿掉。
 * 只是不让人看到按钮，真正挡人的是后端的 @PreAuthorize
 */
export const vPerm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const store = useUserStore()
    const needed = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (!needed.some((perm) => store.hasPerm(perm))) {
      el.parentNode?.removeChild(el)
    }
  },
}
