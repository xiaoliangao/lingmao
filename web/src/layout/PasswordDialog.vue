<script setup lang="ts">
import { reactive, ref, useId, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { useTabsStore } from '@/stores/tabs'

// 自己改密码：旧密码对不对后端判断（错了弹"旧密码不对"，对话框不关，改了再提交）；新密码 8-32 位，和后端规则一样。
// 改好后端就作废了这次登录（所有设备），这里清掉本地、回登录页；别的页签有没保存的先问一声（agy 审查 T-6c00a7）
const visible = defineModel<boolean>({ required: true })

const formId = useId()
const formRef = ref<FormInstance>()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '新密码长度 8-32 位', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => callback(value === form.oldPassword ? new Error('新密码和旧密码一样，换一个') : undefined),
      trigger: 'blur',
    },
  ],
  confirm: [
    { required: true, message: '请再输一次新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => callback(value === form.newPassword ? undefined : new Error('两次输入的新密码不一样')),
      trigger: 'blur',
    },
  ],
}

// 先填了确认、又回去改新密码：确认那一栏的对错跟着重新判断，不然旧的红字一直挂着
watch(
  () => form.newPassword,
  () => {
    if (form.confirm) formRef.value?.validateField('confirm').catch(() => {})
  },
)

const saving = ref(false)
// 填了一半按 Esc、点 ×：先问一声
const guard = useUnsavedChanges(form, visible)

const router = useRouter()
const store = useUserStore()
const tabs = useTabsStore()

async function submit() {
  await formRef.value?.validate()
  const dirty = tabs.dirtyTitles()
  if (dirty.length) {
    await ElMessageBox.confirm(`「${dirty.join('」「')}」还没保存。改好密码要重新登录，没保存的会丢。`, '改密码', {
      confirmButtonText: '还是改',
      cancelButtonText: '先去保存',
      confirmButtonClass: 'el-button--danger',
    })
  }
  saving.value = true
  try {
    await authApi.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
  } finally {
    saving.value = false
  }
  guard.markSaved()
  visible.value = false
  store.forget()
  tabs.reset()
  ElMessage.success('密码改好了，请用新密码重新登录')
  router.replace('/login')
}

// 关掉就清空，密码不留在页面里
function clear() {
  Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
}

// 关的时候焦点离开输入框，还会补一次失焦校验（这时已经清空，挂上"请输入…"），所以打开时再清一遍校验结果
function onOpen() {
  formRef.value?.clearValidate()
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="修改密码"
    width="min(440px, 94vw)"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
    @open="onOpen"
    @closed="clear"
  >
    <el-form :id="formId" ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <!-- autocomplete 告诉浏览器的密码管理器哪个是旧密码、哪个是新密码，它才不会把旧密码填进新密码栏 -->
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" autocomplete="current-password" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" autocomplete="new-password" show-password placeholder="8-32 位" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirm">
        <el-input v-model="form.confirm" type="password" autocomplete="new-password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <!-- 按钮在对话框底部、表单外面，用 form 属性挂到表单上：点它、在输入框里按回车，都是提交这个表单 -->
      <el-button type="primary" native-type="submit" :form="formId" :loading="saving">修改密码</el-button>
    </template>
  </el-dialog>
</template>
