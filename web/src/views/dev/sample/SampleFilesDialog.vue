<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { sampleApi, sampleOrderApi } from '@/api/sample'
import type { SampleDetail, SampleImagesResult, SampleOrder, SampleOrderFilesResult } from '@/types/sample'
import type { FileVO } from '@/types/system'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import ImageUpload from '@/components/ImageUpload.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import SampleFeedback from './SampleFeedback.vue'
import { orderScope } from './common'
import { useSampleAction } from './useSampleAction'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'

const props = defineProps<{ sample: SampleDetail; order?: SampleOrder }>()
const visible = defineModel<boolean>({ required: true }), user = useUserStore()
const emit = defineEmits<{ saved: [result: SampleImagesResult | SampleOrderFilesResult] }>()
const original = props.sample, order = props.order, files = ref<FileVO[]>([])
const ready = ref(false), loading = ref(false), error = ref<string | null>(null)
const scope = () => !visible.value ? null : props.order ? `files:${orderScope(props.order)}` : `images:${props.sample.companyId}:${props.sample.id}:${props.sample.version}`
const allowed = computed(() => user.hasPerm('dev:sample:edit') && (props.order ? props.order.current && props.order.actions.edit : props.sample.actions.edit))
const guard = useUnsavedChanges(files, visible, { scope, ready: () => ready.value })
const context = useSampleContext(() => { visible.value = false }), readFiles = useSampleRead()
const action = useSampleAction<{ files: FileVO[] }, SampleImagesResult | SampleOrderFilesResult>(scope, () => allowed.value,
  (body, options) => order
    ? sampleOrderApi.replaceFiles(order.id, order.companyId, { version: order.version, sampleVersionLock: order.sampleVersionLock }, body.files, options)
    : sampleApi.images(original.id, original.companyId, original.version, body.files, options))
async function load() {
  const read = readFiles.begin(); loading.value = true; ready.value = false; error.value = null
  if (!user.hasPerm('dev:sample:list')) { loading.value = false; error.value = '当前没有读取权限'; return }
  try {
    const result = order ? await sampleOrderApi.files(order.id, order.companyId, { signal: read.signal, silent: true }) : original.images
    if (!read.current()) return
    files.value = result.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size }))
    ready.value = true; guard.markSaved()
  } catch (cause) { if (read.current()) error.value = sampleError(cause) }
  finally { if (read.current()) loading.value = false }
}
async function save() {
  if (!ready.value) return
  const current = context.capture()
  if (!await guard.waitForUploads() || !current()) return
  const receipt = await action.execute({ files: [...files.value] }, original.companyId)
  if (receipt && current()) { guard.discardDraft(); visible.value = false; emit('saved', receipt) }
}
onMounted(load)
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" :title="order ? `第 ${order.sampleVersion} 版 · 打样附件` : '样衣图片'" width="640px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <SampleFeedback :loading="loading" :error="error" label="附件" @retry="load" />
    <p v-if="!allowed && ready" class="dim">当前只读，历史附件仍可查看。</p>
    <template v-if="ready"><DocAttachments v-if="order" v-model="files" :max="20" :disabled="!allowed || action.locked.value" /><ImageUpload v-else v-model="files" :max="20" :disabled="!allowed || action.locked.value" /></template>
    <SampleFeedback :error="action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">关闭</el-button><el-button v-if="allowed" type="primary" :disabled="!ready" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : '保存附件' }}</el-button></template>
  </el-dialog>
</template>
