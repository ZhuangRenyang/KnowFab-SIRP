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
  /*
   * vue-i18n 的 esm-bundler 版本靠这三个全局常量做条件编译。
   * 不 define 的话它们保持为未定义的裸变量，esbuild 把相关分支当 falsy 直接裁掉，
   * 结果「消息编译器」整个消失 —— 运行时 t() 拿不到预编译函数、又没法编译，
   * 直接抛 SyntaxError，组件渲染中断（设备监控页白屏就是这么来的）。
   */
  define: {
    __VUE_I18N_FULL_INSTALL__: true,
    __VUE_I18N_LEGACY_API__: false,
    __INTLIFY_PROD_DEVTOOLS__: false
  },
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
