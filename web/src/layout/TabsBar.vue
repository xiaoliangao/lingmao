<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { HOME_TAB, useTabsStore, type Tab } from '@/stores/tabs'

// 页签：开过的页面都排在这里，点一下切回去，页面原来的样子还在（keep-alive）。首页关不掉；
// 页面里有没保存的修改（useUnsavedChanges 登记的），关之前问一声。中键点页签也能关
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

async function close(tab: Tab) {
  if (tab.path === HOME_TAB.path) return
  if (tabs.isDirty(tab.path)) {
    try {
      await ElMessageBox.confirm(`「${tab.title}」有没保存的修改，关掉以后就没了。`, '关掉页签', {
        confirmButtonText: '不保存，关掉',
        cancelButtonText: '继续编辑',
        confirmButtonClass: 'el-button--danger',
      })
    } catch {
      return
    }
  }
  const next = tabs.close(tab.path, route.path)
  if (next !== route.path) router.push(next)
}
</script>

<template>
  <nav class="ptabs" aria-label="打开的页面">
    <div
      v-for="tab in tabs.tabs"
      :key="tab.path"
      class="ptab"
      :class="{ on: route.path === tab.path }"
      @auxclick.prevent="close(tab)"
    >
      <router-link :to="tab.path" :aria-current="route.path === tab.path ? 'page' : undefined">{{ tab.title }}</router-link>
      <button v-if="tab.path !== HOME_TAB.path" type="button" class="x" :aria-label="`关掉页签：${tab.title}`" title="关掉" @click="close(tab)">
        <el-icon><Close /></el-icon>
      </button>
    </div>
  </nav>
</template>

<style scoped>
/* 样子照原型的 .ptabs / .ptab：页签条是侧边栏的暖灰，当前页签白底带边框，和下面的页面连成一片 */
.ptabs {
  display: flex;
  flex: none;
  align-items: flex-end;
  gap: 2px;
  height: 38px;
  padding: 0 12px;
  overflow-x: auto;
  scrollbar-width: none;
  background: var(--lm-greige);
  border-bottom: 1px solid var(--lm-line);
}

.ptabs::-webkit-scrollbar {
  display: none;
}

.ptab {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  height: 30px;
  margin-bottom: 1px;
  padding: 0 8px 0 0;
  font-size: 12.5px;
  color: var(--lm-ink-2);
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: 7px;
  transition:
    background 0.12s,
    color 0.12s;
}

/* 整个页签都能点：字的左右留白放在链接上 */
.ptab a {
  display: flex;
  align-items: center;
  align-self: stretch;
  padding-left: 12px;
  color: inherit;
  text-decoration: none;
  border-radius: inherit;
}

/* 首页没有 ×，右边留白和左边一样 */
.ptab:not(:has(.x)) {
  padding-right: 12px;
}

.ptab:hover {
  color: var(--lm-ink);
  background: var(--lm-hover);
}

.ptab.on {
  height: 33px;
  margin-bottom: -1px;
  font-weight: 600;
  color: var(--lm-ink);
  background: var(--lm-paper);
  border-color: var(--lm-line);
  border-bottom-color: var(--lm-paper);
  border-radius: 8px 8px 0 0;
}

/* 关掉的 ×：悬停、当前页签、键盘移到这个页签上时才显示 */
.x {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  padding: 0;
  font-size: 11px;
  color: var(--lm-muted);
  visibility: hidden;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 4px;
}

.ptab:hover .x,
.ptab.on .x,
.ptab:focus-within .x {
  visibility: visible;
}

.x:hover {
  color: var(--lm-ink);
  background: var(--lm-sunken);
}
</style>
