import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// 知造 SIRP 前端基座（ADR-001/002）
// dev：5173 端口，/api /ws /open 代理到本机 8080 后端
// build：产物进 ../src/main/resources/static/app，emptyOutDir 只清 app 子目录，
//        static 根下的 screen.html/fault.html 不受影响
export default defineConfig({
  base: '/app/',
  plugins: [vue(), Components({ resolvers: [ElementPlusResolver()] })],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/open': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/ws': { target: 'ws://127.0.0.1:8080', ws: true }
    }
  },
  build: {
    outDir: fileURLToPath(new URL('../src/main/resources/static/app', import.meta.url)),
    emptyOutDir: true,
    assetsDir: 'assets'
  }
})
