import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把 API 请求代理到本地 Spring Boot；生产构建由后端托管静态资源
export default defineConfig({
  plugins: [vue()],
  // 工作区目录是指向 Program Files 的联接（junction），Node 会把路径解析成真实路径，
  // 导致 Vite 的模块 id 与磁盘路径不一致（冷启动首次加载偶发 "Failed to load url /src/main.ts"）。
  // 关闭符号链接解析后，Vite 始终用联接路径，问题消失。
  resolve: { preserveSymlinks: true },
  server: {
    port: 5173,
    // 端口被占用时直接报错，而不是悄悄换到 5174（否则浏览器打不开配置里的地址）
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  },
  build: { outDir: 'dist', assetsDir: 'assets' }
})

