<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { APP_MARK, APP_NAME } from '@/config'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

/**
 * 第一次登录先改密码（RFC 018 §2）：管理员导入、新建、重置密码后，这个人登录进来只到这一页（路由守卫管），
 * 别的接口后端都回 403。改好以后这次登录作废，回登录页用新密码再登一次，菜单、权限照常加载。
 * 不想改可以退出
 */
const store = useUserStore()
const router = useRouter()
const formRef = ref<FormInstance>()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入管理员给你的密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '新密码长度 8-32 位', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => callback(value === form.oldPassword ? new Error('新密码和原来的一样，换一个') : undefined),
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

// 先填了确认、又回去改新密码：确认那一栏的对错跟着重新判断
watch(
  () => form.newPassword,
  () => {
    if (form.confirm) formRef.value?.validateField('confirm').catch(() => {})
  },
)

const saving = ref(false)
async function submit() {
  await formRef.value?.validate()
  saving.value = true
  try {
    await authApi.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
  } finally {
    saving.value = false
  }
  // 后端改密码时作废了这次登录：只清本地，回登录页（登录成功时整页重新加载，菜单路由按新登录生成）
  store.forget()
  ElMessage.success('密码改好了，请用新密码登录')
  router.replace('/login')
}

async function logout() {
  try {
    await store.logout()
  } finally {
    router.replace('/login')
  }
}
</script>

<template>
  <div class="page-wrap">
    <main class="box">
      <p class="brand">
        <b class="mark" aria-hidden="true">{{ APP_MARK }}</b>
        <span>{{ APP_NAME }}</span>
      </p>
      <el-form
        ref="formRef"
        class="card"
        :model="form"
        :rules="rules"
        label-position="top"
        hide-required-asterisk
        @submit.prevent="submit"
      >
        <h1>先改一下密码</h1>
        <p class="lead">
          {{ store.info?.nickname ?? '你' }}，你现在用的是管理员给的密码。改成只有自己知道的密码，才能开始用系统。
        </p>
        <!-- autocomplete 告诉浏览器的密码管理器哪个是旧密码、哪个是新密码 -->
        <el-form-item label="管理员给的密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" autocomplete="new-password" show-password placeholder="8-32 位" />
        </el-form-item>
        <el-form-item label="再输一次新密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving" class="submit">改好了，重新登录</el-button>
        <p class="hint">
          不是你的账号？<el-button link class="logout" @click="logout">退出</el-button>
        </p>
      </el-form>
    </main>
  </div>
</template>

<style scoped>
/* 和登录页一个样子：浅暖灰底上一张白卡片 */
.page-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px 16px 56px;
  background: var(--lm-greige-2);
}

.box {
  width: min(380px, 100%);
}

.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 20px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  font-size: 14px;
  line-height: 1;
  color: var(--lm-paper);
  background: var(--lm-ink);
  border-radius: 7px;
}

.card {
  padding: 26px 28px 22px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-dialog);
  box-shadow: var(--lm-shadow-pop);
}

h1 {
  margin-bottom: 8px;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.lead {
  margin-bottom: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--lm-muted);
}

.card :deep(.el-input__wrapper) {
  min-height: 36px;
}

.submit {
  width: 100%;
  height: 36px;
  margin-top: 6px;
  font-size: 13px;
}

.hint {
  margin-top: 14px;
  font-size: 12px;
  color: var(--lm-muted);
  text-align: center;
}

.logout {
  font-size: 12px;
  vertical-align: baseline;
}
</style>
