<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { APP_MARK, APP_NAME } from '@/config'
import { useVersion } from '@/composables/useVersion'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const { version, title: versionTitle } = useVersion()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)

async function submit() {
  await formRef.value?.validate()
  loading.value = true
  try {
    await store.login(form.username, form.password)
    // 整页跳转：重新加载一次，按这个人的权限生成路由和菜单
    location.href = (route.query.redirect as string | undefined) ?? '/'
  } finally {
    loading.value = false
  }
}

// 已经登录了还打开登录页，直接回首页
if (store.token) {
  router.replace('/')
}
</script>

<template>
  <div class="login">
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
        <h1>登录</h1>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
        <p class="hint">忘记密码请找管理员重置</p>
      </el-form>
    </main>
    <p v-if="version" class="version" :title="versionTitle">版本 <span class="code">{{ version }}</span></p>
  </div>
</template>

<style scoped>
/* 照原型的样子：浅暖灰底上一张白卡片，左上角那个小黑方块和侧边栏的一样 */
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  /* 底下留出版本号的位置，窗口矮的时候也不会和卡片叠在一起 */
  padding: 24px 16px 56px;
  background: var(--lm-greige-2);
}

.box {
  width: min(360px, 100%);
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
  margin-bottom: 18px;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.015em;
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
  color: var(--lm-faint);
  text-align: center;
}

.version {
  position: absolute;
  right: 0;
  bottom: 20px;
  left: 0;
  font-size: 12px;
  color: var(--lm-faint);
  text-align: center;
}
</style>
