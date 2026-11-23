import { defineConfig } from 'vitest/config'

// 单元测试配置；不需要 Vue 插件，仅运行纯 TS 工具函数测试
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts']
  }
})
