<script setup lang="ts">
// 空白提示：一张没写字的吊牌 + 标题 + 说明 + 操作按钮。404、功能没上线时用
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="blank-state">
    <div class="hang-tag" aria-hidden="true" />
    <h2>{{ title }}</h2>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.blank-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 72px 24px;
  text-align: center;
}

/* 吊牌：圆角矩形 + 顶上一个穿绳的孔，虚线边表示"还没内容" */
.hang-tag {
  position: relative;
  width: 64px;
  height: 88px;
  margin-bottom: 20px;
  background: var(--lm-paper);
  border: 1.5px dashed #b7bcc4;
  border-radius: 6px 6px 8px 8px;
  transform: rotate(-6deg);
}

.hang-tag::before {
  position: absolute;
  top: 10px;
  left: 50%;
  width: 10px;
  height: 10px;
  content: '';
  border: 1.5px solid #b7bcc4;
  border-radius: 50%;
  transform: translateX(-50%);
}

h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

p {
  max-width: 28em;
  margin: 8px 0 0;
  color: var(--lm-muted);
  text-wrap: balance;
}

.actions {
  margin-top: 20px;
}
</style>
