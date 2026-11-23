# 贡献指南

感谢你对 TongLink Studio 的关注！欢迎以任何形式参与：提交 Issue、改进文档、修复 Bug 或贡献新节点。

## 提交 Issue

- 描述问题时请给出**复现步骤**、**期望行为**与**实际行为**。
- 附上版本号、操作系统、JDK / Node 版本；如涉及前端问题，请附浏览器截图。
- 安全问题请勿在公开 Issue 中披露，直接联系维护者。

## 开发环境

- 后端：JDK 21 + Maven 3.9
- 前端：Node.js 20+
- IDE：IntelliJ IDEA / VS Code，配置见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)

```bash
# 后端
cd backend && mvn test

# 前端
cd frontend && npm install && npm test && npm run build
```

## 代码规范

- 全中文注释与文档；变量、方法命名使用英文小驼峰。
- 后端按现有 Maven 模块划分职责，不跨模块直接访问内部实现。
- 前端组件使用 Vue 3 `<script setup>` + TypeScript，样式集中在 `styles.css`。
- 新增节点需要同时提供：后端执行器（或通用执行器配置）、`node-definitions.json` 定义、前端图标与属性面板 Schema。

## 提交流程

1. Fork 本仓库并创建特性分支：`git checkout -b feat/your-feature`
2. 提交时写清楚改动动机，例如 `feat: 增加 MQTT 节点重连配置`
3. 确保 `mvn test` 与 `npm test` 全部通过
4. 发起 Pull Request，说明改动内容与测试情况

## 行为准则

尊重每一位参与者，理性讨论技术方案。禁止任何形式的骚扰、歧视与人身攻击。

## 协议

提交代码即表示你同意本项目的 [MIT License](LICENSE)。
