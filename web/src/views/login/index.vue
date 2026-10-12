<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

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
    <el-card class="card">
      <h2>领猫 SCM</h2>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="submit" @click="submit">登 录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f3a5f, #4a7ab0);
}

.card {
  width: 360px;
}

h2 {
  text-align: center;
  margin: 0 0 24px;
}

.submit {
  width: 100%;
}
</style>
