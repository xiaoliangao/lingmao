<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '@/api/system'
import type { FileVO } from '@/types/system'
import { compressImage } from '@/utils/image'
import { MAX_UPLOAD_SIZE } from '@/utils/request'

/**
 * 多张图片，第一张是主图（款式、物料、样衣，PRD §5.8）。v-model 是 FileVO 数组，数组的顺序就是显示的顺序。
 * 选好的图先在浏览器里压缩再传。删除只是从数组里拿掉，文件还在服务器上（上传的文件只增不删，RFC 001 §10）
 *
 *   <ImageUpload v-model="form.images" />                      最多 9 张
 *   <ImageUpload v-model="form.images" :max="3" />
 *   <ImageUpload :model-value="detail.images" disabled />       只看，不能改
 */
const props = withDefaults(defineProps<{ max?: number; disabled?: boolean }>(), { max: 9, disabled: false })
const model = defineModel<FileVO[]>({ default: () => [] })

/** 正在压缩、上传的图，传完以后换成真的图 */
interface Pending {
  id: number
  name: string
  status: '压缩中' | '上传中'
}
const pending = ref<Pending[]>([])
let lastId = 0

const input = ref<HTMLInputElement>()
/** 还能再加几张 */
const room = computed(() => props.max - model.value.length - pending.value.length)
const previews = computed(() => model.value.map((image) => image.url))

async function onPick(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  // 清空，删掉的图还能再选一次
  target.value = ''
  const images = files.filter((file) => file.type.startsWith('image/'))
  if (images.length < files.length) {
    ElMessage.warning('只能传图片，选中的其他文件没有添加')
  }
  const accepted = images.slice(0, Math.max(room.value, 0))
  if (accepted.length < images.length) {
    ElMessage.warning(`最多 ${props.max} 张，多出来的 ${images.length - accepted.length} 张没有添加`)
  }
  if (accepted.length > 0) {
    await add(accepted)
  }
}

async function add(files: File[]) {
  const batch = files.map((file) => reactive<Pending>({ id: ++lastId, name: file.name, status: '压缩中' }))
  pending.value.push(...batch)
  const uploads: Promise<FileVO | null>[] = []
  // 一张一张压：手机上同时解码几张大照片，内存会不够。压好一张就开始传，传的同时压下一张
  for (const [index, file] of files.entries()) {
    const compressed = await compressImage(file)
    if (compressed.size > MAX_UPLOAD_SIZE) {
      ElMessage.error(`${file.name} 超过 ${MAX_UPLOAD_SIZE / 1024 / 1024}MB，传不了`)
      uploads.push(Promise.resolve(null))
      continue
    }
    batch[index]!.status = '上传中'
    // 传失败的那张，消息拦截器已经弹过了，跳过它，别的照常
    uploads.push(fileApi.upload(compressed).catch(() => null))
  }
  const uploaded = (await Promise.all(uploads)).filter((file): file is FileVO => file !== null)
  const ids = new Set(batch.map((item) => item.id))
  pending.value = pending.value.filter((item) => !ids.has(item.id))
  // 等这一批都传完，按选的顺序一起加：先传完的不会抢到前面去当主图
  if (uploaded.length > 0) {
    model.value = [...model.value, ...uploaded]
  }
}

function setMain(index: number) {
  const images = [...model.value]
  const [picked] = images.splice(index, 1)
  model.value = [picked!, ...images]
}

function remove(index: number) {
  model.value = model.value.filter((_, i) => i !== index)
}
</script>

<template>
  <div class="image-upload">
    <ul class="grid">
      <li v-for="(image, index) in model" :key="image.url" class="tile">
        <el-image
          class="thumb"
          :src="image.thumbUrl || image.url"
          :alt="image.name"
          fit="cover"
          :preview-src-list="previews"
          :initial-index="index"
          preview-teleported
          hide-on-click-modal
        />
        <span v-if="index === 0" class="main-badge">主图</span>
        <div v-if="!disabled" class="actions">
          <button v-if="index > 0" type="button" @click="setMain(index)">设为主图</button>
          <button type="button" class="danger" @click="remove(index)">删除</button>
        </div>
      </li>
      <li v-for="item in pending" :key="`pending-${item.id}`" class="tile pending" :title="item.name">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ item.status }}</span>
      </li>
      <li v-if="!disabled && room > 0">
        <button type="button" class="tile add" @click="input?.click()">
          <el-icon><Plus /></el-icon>
          <span>添加图片</span>
          <small class="num">{{ model.length + pending.length }} / {{ max }}</small>
        </button>
      </li>
    </ul>
    <p v-if="disabled && model.length === 0" class="empty">没有上传图片</p>
    <input ref="input" type="file" accept="image/*" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.tile {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 104px;
  height: 104px;
  overflow: hidden;
  background: var(--lm-greige-2);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}

.thumb {
  display: block;
  width: 100%;
  height: 100%;
}

.main-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
  color: var(--lm-paper);
  pointer-events: none;
  background: var(--lm-indigo-600);
  border-radius: 3px;
}

/* 操作条：鼠标移上去、键盘移到按钮上时出现；手机上没有悬停，一直显示 */
.actions {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  justify-content: space-around;
  background: var(--lm-paper);
  border-top: 1px solid var(--lm-line-soft);
  opacity: 0;
  transition: opacity 0.15s;
}

.tile:hover .actions,
.tile:focus-within .actions {
  opacity: 1;
}

@media (hover: none) {
  .actions {
    opacity: 1;
  }
}

.actions button {
  flex: 1;
  padding: 4px 0;
  font: inherit;
  font-size: 12px;
  color: var(--lm-indigo-600);
  cursor: pointer;
  background: none;
  border: 0;
}

.actions button + button {
  border-left: 1px solid var(--lm-line-soft);
}

.actions button:hover {
  background: var(--lm-indigo-50);
}

.actions button.danger {
  color: var(--lm-danger-text);
}

.actions button.danger:hover {
  background: var(--lm-danger-bg);
}

.pending {
  gap: 6px;
  font-size: 12px;
  color: var(--lm-muted);
}

.pending .el-icon {
  font-size: 20px;
}

/* 添加：虚线框，像还没缝上的布片 */
.add {
  gap: 2px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-muted);
  cursor: pointer;
  border-style: dashed;
}

.add .el-icon {
  margin-bottom: 4px;
  font-size: 22px;
}

.add small {
  font-size: 12px;
}

.add:hover {
  color: var(--lm-indigo-600);
  border-color: var(--lm-indigo-600);
}

.empty {
  margin: 0;
  font-size: 13px;
  color: var(--lm-muted);
}
</style>
