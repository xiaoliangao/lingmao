import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时前端跑在 5173，/api 和 /files 转发给后端（默认 IDEA 里的 8080）
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET || 'http://localhost:8080'
  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      proxy: { '/api': target, '/files': target },
    },
    build: {
      // Element Plus 单独打一个包：它很少变，浏览器缓存住以后，发新版只需要重新下载业务代码
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            'element-plus': ['element-plus', '@element-plus/icons-vue'],
            vue: ['vue', 'vue-router', 'pinia', 'axios'],
          },
        },
      },
    },
  }
})
