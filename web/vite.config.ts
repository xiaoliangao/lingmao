import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { APP_NAME } from './src/config'

// 开发时前端跑在 5173，/api 和 /files 转发给后端（默认 IDEA 里的 8080），/assistant 转发给智能助手服务（默认 8091，没起的话助手显示"暂时用不了"）
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET || 'http://localhost:8080'
  const assistantTarget = env.VITE_ASSISTANT_TARGET || 'http://localhost:8091'
  return {
    plugins: [
      vue(),
      // index.html 里的 %APP_NAME% 换成 src/config.ts 的系统名，名字只写一处
      { name: 'app-name', transformIndexHtml: (html) => html.replaceAll('%APP_NAME%', APP_NAME) },
    ],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      // Keep the browser's Host (including port) aligned with Origin for the server's same-origin checks.
      // Vite's string shorthand would silently enable changeOrigin and make these requests cross-origin upstream.
      proxy: {
        '/api': { target, changeOrigin: false },
        '/files': { target, changeOrigin: false },
        '/assistant': { target: assistantTarget, changeOrigin: false },
      },
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
