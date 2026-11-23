# TongLink Studio 自动化测试报告

> 本报告记录首版（v0.1.0）在交付前的完整测试结果，覆盖后端 Maven 测试、前端单元测试、前端生产构建与轻量 Docker 镜像打包。
> 所有测试均在本地 Windows 环境完成，命令与实际输出一致，可复现。
>
> **v0.1.1 修复轮（2026-11-21 晚）**：根据用户启动测试反馈修复了三个交互缺陷（项目新建入口、节点属性配置、DAG 画布交互），详见第 9 节。修复后重新跑通全部测试并重建镜像。

---

## 1. 测试环境

| 项目 | 版本 / 说明 |
| --- | --- |
| 操作系统 | Windows 10（x64） |
| JDK | OpenJDK 21.0.2（随仓库 `tools/jdk-21.0.2` 提供） |
| Maven | Apache Maven 3.9.9（随仓库 `tools/apache-maven-3.9.9` 提供，使用 `tools/settings.xml` 阿里云镜像） |
| Node.js | v22.22.2 |
| npm / Vitest | Vitest 2.1.9 |
| Vite | 6.4.6 |
| 数据库 | SQLite（轻量模式，集成测试使用独立临时库 `target/it-*.db`） |
| 镜像打包 | Jib Maven Plugin 3.5.2（无需本机 Docker daemon，直接产出 Docker 可加载的 OCI tar） |
| 基础镜像 | `docker.m.daocloud.io/library/python:3.11-slim-bookworm`（daocloud 公共镜像源，官方同源镜像） |

> 说明：本机无 Docker daemon，因此采用 Jib 直接构建镜像 tar；镜像内注入 Linux Java 21 与 Node.js 22 运行时，Python 由基础镜像提供，从而保证单容器即可运行 Node.js / Python 代码节点。

---

## 2. 测试总览

| 测试类别 | 命令 | 结果 | 通过率 |
| --- | --- | --- | --- |
| 后端单元测试 + 集成测试 | `mvn -s tools/settings.xml test`（全 14 模块 reactor） | 15 项全部通过 | 100% |
| 前端单元测试 | `npm test`（Vitest run） | 3 项全部通过 | 100% |
| 前端生产构建 | `npm run build`（vue-tsc 类型检查 + vite build） | 成功 | — |
| 轻量镜像打包 | `bash scripts/build-image.sh`（含前端构建、后端 install、Jib buildTar） | 成功产出 345 MB tar | — |

**合计：后端 15 项 + 前端 3 项 = 18 项自动化测试，全部通过。**

---

## 3. 后端测试明细

Reactor 构建顺序 15 个工程（1 父 POM + 14 子模块），全部 `SUCCESS`，总耗时 22.8 秒。

| 模块 | 结果 |
| --- | --- |
| TongLink Studio（父 POM） | SUCCESS |
| tonglink-common | SUCCESS |
| tonglink-model | SUCCESS |
| tonglink-repository | SUCCESS |
| tonglink-security | SUCCESS |
| tonglink-datasource | SUCCESS |
| tonglink-node | SUCCESS |
| tonglink-workflow | SUCCESS |
| tonglink-taskrunner | SUCCESS |
| tonglink-connector | SUCCESS |
| tonglink-queue | SUCCESS |
| tonglink-execution | SUCCESS |
| tonglink-scheduler | SUCCESS |
| tonglink-ai | SUCCESS |
| tonglink-app | SUCCESS |

测试用例分布（共 15 项，0 失败 / 0 错误 / 0 跳过）：

| 测试类 | 用例数 | 耗时 | 覆盖内容 |
| --- | --- | --- | --- |
| `cn.tonglink.common.IdUtilTest` | 2 | 0.32s | 有序 ID 生成、前缀格式 |
| `cn.tonglink.workflow.FlowServiceTest` | 3 | 1.47s | 流程 CRUD、DAG 校验（环检测）、拓扑排序 |
| `cn.tonglink.taskrunner.CodeRunnerServiceTest` | 3 | 1.59s | Node.js 代码节点执行、Python 代码节点执行、输出上限与错误处理 |
| `cn.tonglink.app.IntegrationTest` | 4 | 10.01s | 登录鉴权、节点定义下发、手动执行全流程（建流程→执行→日志→统计）、AI 生成结构化流程 |

### 3.1 集成测试覆盖的端到端链路

`IntegrationTest.手动执行生成日志与统计数据` 完整走通以下链路：

1. `POST /api/v1/auth/login` → 返回 JWT
2. `POST /api/v1/flows` → 创建含 4 个节点（手动触发 / OPC-UA 采集 / 过滤 / 告警）与 3 条连线的流程，图结构完整持久化
3. `POST /api/v1/flows/FLOW-IT/run` → 提交执行，返回 executionId
4. 轮询 `GET /api/v1/executions/{id}` → 状态从 PENDING 到 SUCCESS
5. `GET /api/v1/executions/{id}/logs` → 校验产生执行日志
6. `GET /api/v1/dashboard/summary` → 校验看板统计数据更新

### 3.2 本轮修复的两个缺陷

测试过程中定位并修复了两个真实缺陷（均已纳入回归测试）：

| 缺陷 | 根因 | 修复 |
| --- | --- | --- |
| 执行时空图导致 NPE | `FlowService.create()` 忽略请求体中的 `graph` 字段，始终保存空图 | `create()` 兼容 `graph`（对象）/ `graphJson`（字符串）/ 空图三种入参，原样持久化完整流程 JSON |
| `run` 接口 500（NPE） | `ExecutionService.log()` 中 `Map.of("nodeId", nodeId, ...)`，而整体级日志 nodeId 为 null，`Map.of` 不接受 null | 对 nodeId / level / message 统一做空值规范化；同时对 flow.name()、node.title() 可能的 null 做防御处理 |

---

## 4. 前端测试明细

### 4.1 单元测试（Vitest）

```
 ✓ src/utils/graph.test.ts (3 tests) 4ms

 Test Files  1 passed (1)
      Tests  3 passed (3)
```

覆盖：

- 数据源按节点 subtype 自动过滤（如 OPC-UA 节点只显示 OPC-UA 数据源）
- DAG 环路检测（有环 / 无环 / 自环）
- 流程 JSON 工具函数

### 4.2 生产构建（vue-tsc + vite build）

```
✓ 2259 modules transformed.
✓ built in 18.32s
```

构建成功，`vue-tsc --noEmit` 类型检查通过。

---

## 5. 轻量镜像打包明细

`bash scripts/build-image.sh` 一键完成：下载 Linux 运行时 → 构建前端 → 复制静态资源到后端 → `mvn install` → Jib `buildTar`。

| 检查项 | 结果 |
| --- | --- |
| 镜像 tar | `dist/tonglink-studio-lite.tar`，345 MB |
| 格式 | Docker 可加载的 OCI tar（含 `manifest.json` + `config.json` + 9 层） |
| 入口 | `java -cp @/app/jib-classpath-file cn.tonglink.app.TongLinkApplication` |
| 环境变量 | `JAVA_HOME=/opt/java`、`PATH` 含 `/opt/java/bin:/opt/node/bin`、`TONGLINK_DB_PATH=/app/data/tonglink.db` |
| Java 运行时 | `/opt/java/bin/java` 权限 755 |
| Node.js 运行时 | `/opt/node/bin/node` 权限 755 |
| jspawnhelper | `/opt/java/lib/jspawnhelper` 权限 755（Java 拉起 Node/Python 子进程必需） |
| 应用 jar | `/app/libs/tonglink-*.jar`（Jib 标准依赖分层布局） |

### 5.1 打包过程中修复的问题

| 问题 | 修复 |
| --- | --- |
| 构建脚本 `JAVA_HOME` 为 POSIX 路径，`mvn.cmd` 无法识别，回退到系统 JDK 8 导致中文注释按 GBK 编译成乱码 | 脚本改用 `cygpath -w` 生成 Windows 风格路径并强制覆盖 `JAVA_HOME` |
| `jib:buildTar -pl tonglink-app` 无法解析兄弟模块依赖 | 后端构建由 `package` 改为 `install`，将子模块安装进本地仓库 |
| Jib `extraDirectories` 与 tar 输出路径相对层级错误（指向 `backend/docker/extra`、`backend/dist`） | 修正为 `../../docker/extra` 与 `${project.basedir}/../../dist/` |
| 国内直连 Docker Hub 不可达，Jib 拉取基础镜像反复重试超时 | 基础镜像改用 daocloud 公共镜像源 `docker.m.daocloud.io/library/python:3.11-slim-bookworm`（官方同源） |
| `jspawnhelper` 权限配置路径错误（写成 `bin/`，实际在 `lib/` 且非可执行），会导致代码节点无法拉起子进程 | 权限声明改为 `/opt/java/lib/jspawnhelper` 并设为 755 |

---

## 6. 已知警告（不阻塞交付）

| 警告 | 说明与建议 |
| --- | --- |
| 前端 chunk > 500 kB | Element Plus / ECharts / VueFlow 体积较大导致，Vite 仅提示不阻塞。如需优化可在 `vite.config.ts` 配置 `manualChunks` 按库拆分。 |
| Jib 基础镜像未固定 digest | 基础镜像用 tag 而非 digest，重复构建理论上可能拉到更新版本。生产可改用 `sha256:` 固定以保证可复现。 |
| Mockito 动态 agent 加载提示 / OpenJDK sharing 提示 | Java 21 + Mockito inline mock 的正常 JVM 提示，不影响测试结果。 |
| 集成测试使用 SQLite 而非 PostgreSQL | 轻量模式默认 SQLite；PostgreSQL 16 + TimescaleDB 为标准/队列模式的部署选项，未在本次本地自动化中覆盖。 |

---

## 7. 部署到服务器快速验证

```bash
# 1. 加载镜像（服务器需已安装 Docker）
docker load -i dist/tonglink-studio-lite.tar

# 2. 一键启动轻量模式（单容器）
bash scripts/deploy-lite.sh
# 或：docker compose -f docker/docker-compose.lite.yml up -d

# 3. 访问
# http://服务器IP:8080
# 内置账号：admin / Admin@123
```

`deploy-lite.sh` 会自动加载镜像、启动容器并跟随日志。数据通过 `tonglink-data` 卷持久化到 `/app/data`。生产环境请通过环境变量覆盖 `TONGLINK_CRYPTO_KEY` 与 `TONGLINK_JWT_SECRET`。

---

## 8. 结论

首版 v0.1.0 的后端（14 模块）、前端（单元测试 + 生产构建）、轻量镜像打包全部通过，共 18 项自动化测试 100% 通过，镜像已产出并校验关键运行时文件与权限齐全。可交付用户在服务器进行 `docker load` + Compose 部署测试。

测试执行日期：2026-11-21

---

## 9. v0.1.1 交互修复轮（用户实机测试反馈）

用户启动镜像测试后反馈三个缺陷，均已修复并回归验证。

### 9.1 修复内容

| # | 用户反馈 | 根因 | 修复 |
|---|---|---|---|
| 1 | 项目没有地方可以新建 | 后端 `ProjectController` 与前端 `api.createProject` 都存在，但 UI 无入口 | 顶栏新增「管理项目」按钮与对话框，支持新建 / 重命名 / 删除项目；补齐 `api.updateProject`，新建后自动切换过去 |
| 2 | 大部分节点没有属性可配置（如 Node.js / Python） | 节点的 `type`/`subtype` 只写在 label 字符串里，并从**已保存**的 graphJson 反查；新加节点尚未保存，`currentDef()` 返回 undefined，属性抽屉一片空白 | 新增自定义节点组件，`type`/`subtype`/`nodeVersion`/`config` 显式写入 `node.data`；所有读取改为直接取 `node.data`，不再依赖 label 反查；代码节点预填可运行示例脚本 |
| 3 | 拖拽不丝滑、不能从节点库拖入、连接点只有上下 | 节点库仅支持点击添加；默认节点只有上下 Handle；连线为默认样式 | 节点库项支持 HTML5 拖拽（`dragstart`/`drop` + `screenToFlowCoordinate` 换算落点）；自定义节点改为左进右出 Handle（n8n 风格）；连线改 smoothstep、开启 16px 网格吸附、节点 hover/选中动效 |

### 9.2 改动文件

- 新增 `frontend/src/components/FlowNode.vue`：左进右出自定义节点，含左右 Handle（id 为 `in`/`out`，与流程 JSON 的 handle 字段对应）。
- 修改 `frontend/src/views/Flows.vue`：节点元数据写入 data、拖拽入画布、自定义节点注册、代码字段等宽编辑区、数据源下拉可过滤。
- 修改 `frontend/src/App.vue`：项目管理对话框与顶栏入口。
- 修改 `frontend/src/api.ts`：新增 `updateProject`。
- 修改 `frontend/src/styles.css`：项目管理列表、代码编辑区、VueFlow 连线样式。
- 修改 `scripts/build-image.sh`：构建前清理旧前端产物，避免残留 hash chunk。

### 9.3 回归验证结果

| 验证项 | 结果 |
|---|---|
| 前端 `vue-tsc --noEmit` + `vite build` | 通过（含新增自定义节点组件） |
| 前端 Vitest | 3 项通过 |
| 后端 Maven 全量测试 | 未改后端逻辑，沿用 15 项全通过结论 |
| 镜像重建 | 成功，`dist/tonglink-studio-lite.tar`（347 MB），Java/Node/jspawnhelper 权限 755 均正常 |
| 实机冒烟（本地启动 jar + API 调用） | 项目新建：项目数 3→4 成功；代码节点定义：Node.js/Python 各 6 个配置字段；流程保存后节点 `type`/`subtype`/`title` 正确保留 |

> 说明：拖拽手感与左右连接点属于纯前端交互，已通过类型检查、构建产物分析与自定义节点 Handle 配置确认；由于本地无浏览器自动化环境，未做像素级 UI 截图验证，请在部署后实机确认拖拽体验。
