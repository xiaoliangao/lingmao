import { computed, defineComponent, h, type VNode } from 'vue'
import { RouterLink, useRouter, type Router } from 'vue-router'
import { PAGE_LINK, parseAnswer, type Block, type Inline } from '@/utils/assistantText'

/**
 * 显示助手答完的全文（done.text、历史里 assistant 的 content），docs/api/08-智能助手.md §3。
 * 不用 v-html：utils/assistantText.ts 拆成块，这里用 h() 拼成元素，文字一律当文字（Vue 会转义）。
 * 链接要过两道：地址是 /页面 或 /页面/数字，而且 router 认得、是业务菜单这一侧的页（不是落到 404 那条，没权限的页面也没注册；
 * 登录页、管理后台、手机报工不算）；不然只显示文字。
 * 点链接在主区域打开那一页，卡片留着；发 navigate，窄屏上卡片差不多盖满页面，自己收起来
 */
export function routable(router: Router, href: string): boolean {
  if (!PAGE_LINK.test(href)) return false
  const target = router.resolve(href)
  // 只认业务菜单这一侧（workbench 下面）的页：/login、/admin、手机报工这些单独的页不在页签里打开（审查 T-c5c68e #3）
  return target.matched[0]?.name === 'workbench' && !target.matched.some((record) => record.path.includes(':pathMatch'))
}

export default defineComponent({
  name: 'AssistantAnswer',
  props: {
    text: { type: String, required: true },
  },
  emits: ['navigate'],
  setup(props, { emit }) {
    const router = useRouter()
    const blocks = computed(() => parseAnswer(props.text))

    const inline = (parts: Inline[]): (VNode | string)[] =>
      parts.map((part) => {
        if (part.kind === 'strong') return h('strong', part.text)
        if (part.kind === 'code') return h('code', part.text)
        if (part.kind === 'link') {
          return routable(router, part.href) ? h(RouterLink, { to: part.href, onClick: () => emit('navigate') }, () => part.text) : part.text
        }
        return part.text
      })

    const block = (b: Block): VNode => {
      switch (b.kind) {
        case 'p':
          return h('p', b.lines.flatMap((line, i) => (i ? [h('br'), ...inline(line)] : inline(line))))
        case 'heading':
          return h('p', { class: 'ast-heading' }, inline(b.content))
        case 'list':
          return h(b.ordered ? 'ol' : 'ul', b.ordered && b.start !== 1 ? { start: b.start } : null, b.items.map((item) => h('li', inline(item))))
        case 'table':
          return h('div', { class: 'ast-table' }, [
            h('table', [
              h('thead', [h('tr', b.head.map((cell) => h('th', inline(cell))))]),
              h('tbody', b.rows.map((row) => h('tr', row.map((cell) => h('td', inline(cell)))))),
            ]),
          ])
        case 'pre':
          return h('pre', b.text)
        case 'hr':
          return h('hr')
      }
    }

    return () => h('div', { class: 'ast-answer' }, blocks.value.map(block))
  },
})
