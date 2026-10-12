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
    <!-- 左边：靛蓝斜纹布上缝着一块织唛，打开页面时金黄明线缝一圈 -->
    <section class="cloth" aria-hidden="true">
      <div class="woven-label">
        <svg class="stitch" viewBox="0 0 320 188" preserveAspectRatio="none">
          <defs>
            <mask id="sew-mask">
              <rect class="sew" x="7" y="7" width="306" height="174" rx="8" pathLength="1000" />
            </mask>
          </defs>
          <rect class="thread" x="7" y="7" width="306" height="174" rx="8" mask="url(#sew-mask)" />
        </svg>
        <p class="mark"><span class="cn">领猫</span><span class="en">SCM</span></p>
        <p class="sub">采购　入库　库存</p>
      </div>
    </section>

    <section class="form-side">
      <el-form ref="formRef" class="form" :model="form" :rules="rules" label-position="top" size="large" hide-required-asterisk @submit.prevent="submit">
        <h1>登录</h1>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
        <p class="hint">忘记密码请找管理员重置。</p>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  height: 100%;
}

/* ---------- 左：布和织唛 ---------- */
.cloth {
  display: flex;
  flex: 0 0 44%;
  align-items: center;
  justify-content: center;
  background: var(--lm-twill), var(--lm-indigo-900);
}

.woven-label {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 320px;
  height: 188px;
  background: rgb(255 255 255 / 4%);
  border-radius: 10px;
}

.stitch {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.thread {
  fill: none;
  stroke: var(--lm-stitch);
  stroke-width: 1.5;
  stroke-dasharray: 7 5;
}

/* 遮罩从 0 画到一整圈，明线就跟着一针一针露出来 */
.sew {
  fill: none;
  stroke: #fff;
  stroke-width: 4;
  stroke-dasharray: 1000;
  stroke-dashoffset: 1000;
  animation: sew 1.6s ease-out 0.2s forwards;
}

@keyframes sew {
  to {
    stroke-dashoffset: 0;
  }
}

.mark {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0;
  line-height: 1;
}

.cn {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #fff;
}

.en {
  font-family: var(--lm-font-num);
  font-size: 40px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--lm-stitch);
}

.sub {
  margin: 18px 0 0;
  padding-top: 14px;
  font-size: 14px;
  letter-spacing: 0.2em;
  color: var(--lm-side-text);
  border-top: 1px solid rgb(255 255 255 / 14%);
}

/* ---------- 右：表单 ---------- */
.form-side {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--lm-paper);
}

.form {
  width: min(340px, 100%);
}

h1 {
  margin: 0 0 24px;
  font-size: 24px;
  font-weight: 600;
}

.submit {
  width: 100%;
  margin-top: 8px;
}

.hint {
  margin: 16px 0 0;
  font-size: 13px;
  color: var(--lm-muted);
}

@media (width < 820px) {
  .login {
    flex-direction: column;
  }

  .cloth {
    flex: 0 0 200px;
  }

  .woven-label {
    transform: scale(0.75);
  }

  .form-side {
    align-items: flex-start;
    padding-top: 32px;
  }
}
</style>
