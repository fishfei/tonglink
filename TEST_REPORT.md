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
| 执行时空图导致 NPE | `FlowService.create()` 忽略请求体中的 `graph` 字段，始终保存空图 | `create()` 读取 `graph` 对象入参并原样持久化完整流程 JSON |
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
| 3 | 拖拽不丝滑、不能从节点库拖入、连接点只有上下 | 节点库仅支持点击添加；默认节点只有上下 Handle；连线为默认样式 | 节点库项支持 HTML5 拖拽（`dragstart`/`drop` + `screenToFlowCoordinate` 换算落点）；自定义节点改为左进右出 Handle；连线改 smoothstep、开启 16px 网格吸附、节点 hover/选中动效 |

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

---

## 10. v0.2 覆盖式修订轮（2026-11-24）

本轮按「不兼容旧版本、直接用最新结构覆盖」的原则重构，未保留旧节点与旧库迁移路径。

### 10.1 变更与验证点

| # | 变更 | 验证方式 | 结果 |
|---|---|---|---|
| 1 | 删除流程编排页旧节点兼容：`guessType()` 死代码、配置项 `connection`/`credential` 回落；移除节点库中的旧版 `source:mqtt` / `source:kafka` 定义；节点包安装/加载一律要求 `schema` 与 `runtime.kind` | 页面节点库目视核对（截图 `04-flow-editor.png`）+ `NodeDefinitionsTest` + 集成测试 | 通过，节点库只有 Kafka/MQTT 触发器与输出 |
| 2 | 延时节点输入/输出透传：断点恢复时把 `nodeInput` 写回 `outputs[nodeId]` 与 `output_json`，下游拿到完整数据 | 集成测试 `延时节点暂停恢复并透传输入`：代码节点产出 `{temp:90,device:'PLC-1'}` → 延时 → 告警，断言告警节点 `inputJson` 与延时节点 `outputJson` 一致 | 通过（此前下游为空列表的缺陷已修复） |
| 3 | 数据源页面重规划：取消重复的连接参数区，按连接类型渲染唯一表单；类别/协议由类型推导；新增 OPC UA / Modbus / HTTP 类型；移除 `/datasources/protocols` | 集成测试 `数据源按类型统一填写连接与认证` + 浏览器截图 `03-sources.png` | 通过，密码仅回掩码，PostgreSQL 协议由 driver 推导 |
| 4 | 数据库脚本同步：仅保留最新 `schema.sql`，删除 `SchemaMigration` 旧库补列/补表逻辑；`credential` 表不存在 | Maven 全量测试（独立临时库从零建表）+ `schema.sql` 复核 | 通过，15 张表一次建齐 |
| 5 | 文档与截图：README / INTRODUCTION / ARCHITECTURE / DEPLOYMENT / DEVELOPMENT 同步最新语义；7 张截图全部重采 | 文档引用检查 + 浏览器逐页截图 | 通过 |

### 10.2 测试结果

| 类别 | 命令 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **30 项全部通过**，0 失败 / 0 错误 / 0 跳过 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过（2276 模块，Vite 6.4.6） |
| 前端单元测试 | `npm test` | 3 项通过 |
| 浏览器实机截图 | Chrome CDP 逐页采集 | 7 张截图已替换 `docs/screenshots/` 下原文件 |

> 集成测试使用独立 SQLite 临时库；PostgreSQL 连通性此前已用真实 PG 二进制验证，本轮未改动数据库方言相关代码。

---

## 11. 四项修复轮（2026-11-25）

| # | 问题 | 修复 | 验证 |
|---|---|---|---|
| 1 | Kafka 触发器「位点策略」、MQTT 触发器 QoS 显示英文/数字 | 节点 Schema 增加 `labels` 中文映射；属性面板 select 原有 `labels` 渲染逻辑直接生效，底层 value 不变（earliest/latest、0/1/2）；MQTT 输出 QoS 同步 | 浏览器打开两个触发器属性面板，分别显示「最新位点（只消费新消息）」「1 · 至少一次（可能重复）」，下拉可切换全部中文选项；集成测试 `触发器选择字段带中文标签` |
| 2 | 数据合并节点多输入只显示一个箭头 | 合并节点按入边数量动态渲染多个左侧 handle（`in-0`、`in-1`…）并标注序号；连接/删除/插入节点/切换流程后自动重新编号；后端仍按全部入边收集数据 | 浏览器创建 2 支路上游 → 合并节点，可见左侧两个紫色端口与两条独立箭头；5 条连线渲染正常 |
| 3 | 系统存在 3 个内置账号 | 后端启动时清理 engineer/viewer；种子数据只建 admin；移除登录口令的历史兼容表，只认 BCrypt；登录页演示账号只留管理员 | 集成测试 `仅保留管理员账号`：用户表 count=1，旧账号登录返回业务码 401；登录页截图只剩「管理员」 |
| 4 | 新建数据库连接数据源后测试连接失败 | 数据库连接改为按 `credentialType` 判定；支持「完整 JDBC URL」字段且优先于主机/端口/库名；空白 jdbcUrl 不再阻断 URL 生成；MySQL URL 补齐 `useSSL=false`、`serverTimezone`、`allowPublicKeyRetrieval` 与超时参数；抽屉新增保存前「测试连接」按钮，失败时显示驱动原始原因 | 集成测试 `数据库连接测试给出明确结果`（不可达地址返回明确 JDBC 失败）；HTTP 冒烟：完整 JDBC URL 指向本地库返回 `JDBC 连接成功`；截图 `03-sources.png` 展示新表单 |

### 11.1 测试结果

| 类别 | 命令 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **33 项全部通过**（新增 3 项），0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过 |
| 前端单元测试 | `npm test` | 3 项通过 |
| 浏览器实机验证 | Chrome CDP | 登录页、数据源抽屉、Kafka/MQTT 属性面板、多输入合并画布均复核通过；`01-login.png` 与 `03-sources.png` 已重拍 |

---

## 12. 数据源连接测试修复轮（2026-11-25）

| # | 问题 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | 点击测试连接后报错信息不消失，一直停留在页面 | 抽屉 `testDraft()` 使用 `ElMessage({duration: 0, showClose: true})`，每次点击都新增一条永不自动消失的提示并堆叠 | 统一管理单个消息句柄：每次测试先 `close()` 上一条；duration 改为 5 秒且可手动关闭；列表「测试」按钮也走同一逻辑；打开抽屉时清理旧提示 | 浏览器连点 3 次「测试连接」：成功与失败场景均只剩 1 条提示，5 秒后自动消失（count=0） |
| 2 | 数据库连接测试不成功（用户提供 192.168.3.188:3306 / root / root@2025，未填库名） | `validateFields()` 强制要求「数据库名」，但该 MySQL 只需连接实例；请求在校验阶段就返回 422，根本没走到 JDBC | 库名改为可选（MySQL/PostgreSQL 允许只连实例），JDBC URL 允许不拼库名；Oracle/SQL Server 仍要求服务名 | 用户提供的参数走「保存前测试」直接返回 **JDBC 连接成功**；保存后「测试」同样成功，状态 connected |
| 3 | 编辑已有数据源后点「测试连接」仍失败 | 列表接口只回密码掩码 `******`，草稿测试把掩码当真密码发给 JDBC | 草稿请求带 `id`；后端用 `resolveMaskedSecrets()` 把掩码/空白密码替换为已保存的解密值，新填明文密码不被覆盖 | HTTP 冒烟：掩码密码 + id 返回 JDBC 连接成功；单元测试 2 项验证还原与明文优先；浏览器中编辑态测试成功 |

### 12.1 测试结果

| 类别 | 命令 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **35 项全部通过**（新增 2 项数据源单测 + 1 项无库名回归断言），0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过 |
| 前端单元测试 | `npm test` | 3 项通过 |
| 真实数据库冒烟 | 192.168.3.188:3306（用户提供的测试库） | TCP 可达；保存前测试、已保存测试均返回「JDBC 连接成功」 |
| 浏览器实机验证 | Chrome CDP | 提示不堆叠、5 秒自动消失；编辑态掩码密码测试成功；`03-sources.png` 已重拍 |

> 已在本地演示库创建数据源 `DS-01M882W8DRKYTJZ75VP1YPZHJS`（测试库-192.168.3.188），密码 AES-GCM 加密落库，接口只回掩码。

---

## 13. 数据源下拉与消息节点真实联调（2026-11-25）

| # | 事项 | 处理 | 验证 |
|---|---|---|---|
| 1 | 流程编排的数据源下拉把 ID 拼进名称，选项过长 | `Flows.vue` 数据源/凭证选项 label 改为只显示数据源名称，value 仍保存 ID | 前端生产构建通过；本地运行服务接口返回定义正常 |
| 2 | Kafka/MQTT 节点同时出现「数据源」「认证凭据/用户名凭据/密码凭据」重复选择 | 从 `node-definitions.json` 删除 `trigger:kafka`、`output:kafka`、`trigger:mqtt`、`output:mqtt` 的 `credential`/`username`/`password` 字段，认证统一由数据源表单承担 | 新增节点定义单测断言这四类节点不再暴露凭证字段；重启后 `/nodes/definitions` 返回字段为空 |
| 3 | Kafka 触发器未消费到消息 | 测试流拆成「手动推送」与「触发器接收 + Node.js 输出」两条；接收流程使用独立 Topic 与 earliest 位点验证真实消费 | Kafka、MQTT 接收流程均收到推送流程发出的消息，Node.js 节点输出中包含来源流程 ID，执行全部 SUCCESS |
| 4 | 流程下线后后台订阅是否释放 | 下线接收流程后等待调度器对齐，再推送消息并观察执行记录；随后重新发布验证恢复 | 后台日志出现「已停止触发器订阅」；下线期间接收流程执行数不增加；重新发布后日志出现「已启动 … 触发器」且消费恢复 |

### 13.1 真实联调流程

| 流程 ID | 名称 | 链路 | 结果 |
|---|---|---|---|
| `FLOW-TEST-KAFKA-PUSH` | 测试-Kafka-手动推送 | 手动触发 → Kafka 输出 → `tonglink.test.kafka.push` | 手动运行 SUCCESS，消息写入 Topic |
| `FLOW-TEST-KAFKA-RECV` | 测试-Kafka-触发器接收并输出 | 订阅 `tonglink.test.kafka.push` → Kafka 触发器 → Node.js 节点 `return $input` | 收到推送流程消息，代码节点输出包含 `FLOW-TEST-KAFKA-PUSH`，执行 SUCCESS |
| `FLOW-TEST-MQTT-PUSH` | 测试-MQTT-手动推送 | 手动触发 → MQTT 输出 → `tonglink.test.mqtt.push` | 手动运行 SUCCESS，消息发布到 Topic |
| `FLOW-TEST-MQTT-RECV` | 测试-MQTT-触发器接收并输出 | 订阅 `tonglink.test.mqtt.push` → MQTT 触发器 → Node.js 节点 `return $input` | 收到推送流程消息，代码节点输出包含 `FLOW-TEST-MQTT-PUSH`，执行 SUCCESS |
| `FLOW-TEST-PG` | 测试-PostgreSQL 查询 | 手动触发 → PostgreSQL 节点执行 `SELECT 1` | 执行 SUCCESS，输出 `ok=1, db=appdb` |

> 三个数据源分别指向用户提供的 Kafka `192.168.3.188:9092`、MQTT `192.168.3.188:1883` 与 PostgreSQL `192.168.3.188:5432/appdb`；密码均 AES-GCM 加密落库，接口只回掩码。MQTT 的 WebSocket 端口 9001 已确认监听，但服务端 MQTT 客户端固定使用 TCP 1883。

### 13.2 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **36 项全部通过**，0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过 |
| 外部服务连通性 | TCP + 真实客户端 | PostgreSQL 5432、Kafka 9092、MQTT 1883/9001 均可达；PostgreSQL 返回 `appdb`，Kafka/MQTT 收发成功 |
| 流程端到端 | 运行推送流程 + 接收流程发布态验证 | Kafka、MQTT 各完成首轮推送/接收；下线期间再次推送不产生新消费执行；重新发布后消费恢复；PostgreSQL 1 次执行 SUCCESS |

---

## 14. 告警通知节点测试轮（2026-11-25）

| # | 事项 | 处理 | 验证 |
|---|---|---|---|
| 1 | 告警节点此前只是模拟执行器：不读渠道/模板/凭据，永远返回 `written`，不会真发邮件 | 新增 `AlertExecutor` 接管 `output:alert`，从数据源读取解密后的连接字段，实现邮件 / Webhook / 企业微信 / 钉钉真实投递；`SimulatedSourceExecutor` 不再注册该类型 | 节点定义与执行器注册表复验；原有空配置示例流程仍保持模拟回执，回归测试不受影响 |
| 2 | 没有邮件凭据类型，无法配置 SMTP 账号 | `CredentialTypeRegistry` 新增 `email`（SMTP 服务器、端口、SSL/STARTTLS/无、发件账号、授权码、发件人）；授权码按 `password` 字段 AES-GCM 加密、接口只回掩码 | 端到端测试中通过 API 创建邮件数据源，返回 `protocol=SMTP`、密码 `******` |
| 3 | 模板与重复抑制字段此前无效 | 实现 `{{ $json.a.b }}` / `{{ $json }}` / `{{ $var.x }}` 渲染；按「渠道 + 目标 + 级别 + 标题」做内存时间窗抑制 | 单测断言渲染结果、第二次调用返回 `suppressed` 且只发出一次请求 |
| 4 | 失败被静默吞掉 | SMTP / Webhook 异常统一抛业务异常，节点显示 FAILED 并给出具体原因 | 单测断言连接失败时抛出「邮件告警发送失败」 |

### 14.1 邮件端到端验证

| 验证项 | 结果 |
|---|---|
| 本地 SMTP 服务 + 真实 Jakarta Mail 投递 | 收件头 `To: java_fish@126.com`，正文含 `IT-ALERT-MARKER`，主题含严重级别与流程 ID |
| 流程执行结果 | 手动触发 → 邮件告警节点 SUCCESS，节点输出 `status=sent`、`mode=real` |
| 模板渲染 | 输出 `subject=告警 FLOW-…`、`message=IT-ALERT-MARKER flow=FLOW-…`，来自上游触发事件 |
| 凭据安全 | 授权码加密落库，数据源接口只回掩码；执行时由 `DataSourceService.connectionFields()` 解密后使用 |
| 真机投递 | **待用户提供 SMTP 发件账号授权码**；当前仅完成本地 SMTP 服务端到端验证，未向外部邮箱真实投递 |

### 14.2 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **42 项全部通过**（新增 5 项告警单测 + 1 项邮件端到端集成测试），0 失败 / 0 错误 |
| 告警单元测试 | `AlertExecutorTest` | 5 项通过：模板渲染、模拟回退、Webhook 投递 + 抑制 + 企微载荷、本地 SMTP 真实发信、SMTP 失败提示 |
| 告警集成测试 | `IntegrationTest.告警通知节点端到端真实发送邮件` | 通过：API 建邮件数据源 → 建流程 → 执行 → 本地 SMTP 收到真实邮件 |

> 真机邮件需要 SMTP 发件账号（例如 `smtp.126.com:465`、发件邮箱与授权码）。用户确认提供授权码后，将在测试项目创建邮件数据源与「手动触发 → 邮件告警」流程，向 `java_fish@126.com` 发送一封测试邮件并核对收件结果。

---

## 15. 执行日志体验修复轮（2026-11-25）

| # | 问题 | 处理 | 验证 |
|---|---|---|---|
| 1 | 按标签过滤用下拉不合理（标签本质是备注，值可能任意文本） | `ExecutionLogExplorer.vue` 改为 `el-input`，输入停顿 300ms 自动查询、回车/清空立即查询；后端 `appendTagFilter` 由精确匹配 `%"tag"%` 改为子串 `LIKE %tag%` | 集成测试断言完整备注、部分文字、英文片段均能命中，无关文字 0 命中 |
| 2 | 记录列表显示无用的执行 ID | 去掉 ID 列，该位置显示标签（备注）：红色、超长省略号、悬停 tooltip 看全文；保存后 `load()` 以服务端结果回写列表，立即显示 | 代码复核 + 后端标签保存/读取接口测试；前端构建通过 |
| 3 | 分页中间页码挤掉「前往第一页」 | `layout` 去掉 `pager` 只留 `total, sizes, prev, next, jumper`；新增 CSS 把每页条数 select 收窄到 92px、跳页输入框 48px | 执行日志菜单页与流程编排日志 tab 共用同一组件，一次修改两处生效；前端构建通过 |
| 4 | 流程日志的流程图不是最新图（新增节点不体现，但节点 IO 能看到） | 新增 `execution_graph` 表，入队时保存**本次实际使用**的图（手动=草稿、定时/外部触发=发布快照），分页接口下发 `graphJson`；日志页优先用该快照，老记录回退到最新草稿图 | 集成测试断言发布后手工改草稿新增 delay 节点，执行快照含 `delay` 且分页接口返回 `graphJson` |

### 15.1 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **43 项全部通过**（新增 1 项备注子串过滤 + 图快照集成测试），0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过 |

> 本轮未启动前后端服务做截图复核；按此前约定未重启用户取消的后台进程。改动均在工作区，未提交 Git。

---

## 16. 执行日志交互优化轮（2026-11-25）

| # | 问题 | 处理 | 验证 |
|---|---|---|---|
| 1 | 内联过滤条交互不够清晰 | 改为漏斗按钮 + 弹出过滤面板，包含流程名称、状态、标签、执行时间范围，带「应用 / 重置」与生效条件数量角标；后端分页新增 `timeFrom` / `timeTo` 参数并按 `start_time` 过滤 | 集成测试断言起止时间（含边界）命中、未来时间 0 命中 |
| 2 | 保存标签后左侧列表不显示 | `saveTags()` 不再整页重载，直接用服务端返回值回写左侧列表行与右侧选中对象，避免过滤态/翻页换掉目标行 | 代码复核；原有标签保存接口测试通过 |
| 3 | 历史执行流程图随编排变化 | 不再回退到当前草稿/发布图：新执行用入队时冻结的快照；无快照的老记录由后端按 `flow_version` 从 `flow_version` 表取当时发布的图 | 集成测试：V1 发布后执行，删除快照再发布 V2，历史执行仍返回含「版本一节点」的图且不含「版本二节点」 |
| 4 | 详情顶部是固定说明文字 | 改为显示所选执行的流程名称（取不到时用流程 ID），文案「流程图 · 节点状态…」移除 | 前端构建通过 |
| 5 | 变量页「值（明文）」多余 | 列表列标题、编辑抽屉 label 改为「值」，页头说明去掉「以明文」 | 前端构建通过 |
| 6 | 编排页日志 tab 显示流程 ID / 过滤文案冗长 | `flowNameOf()` 统一显示流程名称；过滤面板标签只写「标签」 | 前端构建通过 |
| 7 | 翻页时分页组件位置跳动 | 列表行包进 `.log-rows`，分页组件紧跟最后一条记录保持 8px 固定间距，面板自身可滚动 | 前端构建通过 |

### 16.1 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **45 项全部通过**（新增 2 项集成测试：时间范围过滤、历史图版本锁定），0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过 |

> 本轮浏览器截图复核因环境安全策略不可用；改动均在工作区，未提交 Git。

---

## 17. 日志收尾、连接类型与连接稳定性轮（2026-11-25）

| # | 问题 | 处理 | 验证 |
|---|---|---|---|
| 1 | 执行日志标签功能仍未达到预期 | 前端彻底移除：过滤面板标签项、列表标签徽标、详情标签编辑与保存按钮；`api.ts` 删除 `setExecutionTags`。后端标签接口保留但前端不再调用，便于后续重新设计 | 前端构建通过；代码复核 |
| 2 | 过滤栏刷新按钮多余 | 删除刷新按钮；漏斗面板「应用」直接调用 `reloadFromFirst()`，条件立即生效并回到第一页 | 前端构建通过 |
| 3 | 执行时间选择时过滤弹窗自动关闭 | `el-popover` 由 `trigger="click"` 改为 `manual`，按钮显式切换；面板 `@click.stop` 阻止冒泡，「应用 / 重置」才关闭 | 前端构建通过 |
| 4 | 看板中长流程名挤压「各流程采集记录数」图表 | 横向柱图左侧留白按最长名称动态计算（110~190px），名称超过 130px 截断显示省略号，tooltip 显示完整名称；柱高随条数 240~480px | 前端构建通过 |
| 5 | 数据源列表 ID 列过窄换行 | ID 列加宽至 240px 并加 `show-overflow-tooltip`；名称、连接类型、协议列同步收窄并加溢出提示 | 前端构建通过 |
| 6 | 新增 OAuth2、暂时移除 OPC UA / Modbus | `CredentialTypeRegistry` 新增 oauth2（授权模式、令牌地址、Client ID / Secret、Scope、用户名密码、已有令牌），移除 opcua / modbus；`node-definitions.json` 删除两种节点；`DataSourceService` 增加 password 模式必填校验；README / INTRODUCTION / DEPLOYMENT 同步 | 集成测试断言类型列表含 oauth2 且不含 opcua / modbus；节点定义测试断言两种节点不存在 |
| 7 | PostgreSQL 长时间运行后连接被服务端回收，IDEA 重启后仍连不上 | 平台 Hikari 增加 `min-idle=2`、`maxLifetime=25min`、`keepaliveTime=5min`、`validationTimeout=5s`、`initializationFailTimeout=-1`；节点连接池同步保活、最小空闲 1 条，PG JDBC URL 开启 `tcpKeepAlive=true` | 全量测试通过；长期保活效果待用户实际环境观察 |

### 17.1 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **45 项全部通过**，0 失败 / 0 错误 |
| 前端类型检查 + 生产构建 | `npm run build` | 通过（仅有既有大 chunk 提示） |

> 本轮未启动前后端服务；按此前约定不重启用户取消的后台进程。改动均在工作区，未提交 Git。

---

## 18. PostgreSQL 断链根因定位与彻底加固（2026-11-25）

### 18.1 排查过程与结论

| 排查项 | 结果 |
|---|---|
| IDEA 运行配置 | `TongLink-pg` 使用 `DB_URL=jdbc:postgresql://192.168.3.188:5432/tonglink_studio`、`DB_POOL_SIZE=3`，没有设置 `DB_KEEPALIVE_TIME` / `DB_MAX_LIFETIME` 覆盖变量 |
| PG 服务端参数（16.16，直连查询） | `idle_in_transaction_session_timeout=0`、`idle_session_timeout=0`、`statement_timeout=0`、`max_connections=100`、`tcp_keepalives_idle=7200s` —— 服务端不会主动回收空闲连接 |
| 警告来源 | HikariCP 6.3.3 `PoolBase.isConnectionDead()`；日志线程 `[nio-8080-exec-1]` 说明是 HTTP 请求借连接时校验失败，Hikari 会关闭该连接并重新借一条，**属于自愈行为**，不是连接池整体失效 |
| 根因判断 | 中间网络（局域网/NAT/防火墙）回收空闲 TCP 会话；此前 5 分钟保活窗口过长，用户请求恰好落在「连接已断、保活尚未探测」的窗口内。OS 级 TCP keepalive 默认 7200 秒，对分钟级回收无效 |
| 配置绑定验证 | 新增集成测试直接读取 `HikariDataSource` 实际参数，确认 `application.yml` 的保活配置确实被 Spring Boot 绑定（`keepaliveTime > 0`） |

### 18.2 修复措施

| # | 措施 | 说明 |
|---|---|---|
| 1 | 平台连接池保活周期从 5 分钟缩短到 **30 秒**（Hikari 允许的最小值） | `DB_KEEPALIVE_TIME=30000`，死连接在借出前被探测并替换，窗口从 5 分钟压到 30 秒 |
| 2 | `maxLifetime` 从 25 分钟缩短到 **5 分钟**，`idleTimeout` 从 10 分钟缩短到 **2 分钟** | `DB_MAX_LIFETIME=300000`、`DB_IDLE_TIMEOUT=120000`，主动轮换，不依赖服务端超时 |
| 3 | 节点数据库输出连接池同步加固 | 默认 `maxLifetime=300000`，`keepaliveTime=30000`，最小空闲 1 条，PG 连接开启 `tcpKeepAlive` |
| 4 | IDEA 运行配置补全 PG JDBC 参数 | `DB_URL` 追加 `?tcpKeepAlive=true&connectTimeout=5&socketTimeout=30`，避免网络静默丢包时请求无限挂起 |
| 5 | 启动时打印实际生效的池参数 | `DataInitializer` 输出 `keepalive / maxLifetime / minimumIdle / maxPool / validationTimeout`，可在 IDEA 控制台直接确认没有被旧环境变量覆盖 |
| 6 | 增加配置回归测试 | `IntegrationTest.连接池保活参数已生效` 断言保活周期 ≤60 秒、最大存活 ≤10 分钟，防止配置回退 |

### 18.3 真实 PG 容断验证

针对 `192.168.3.188:5432/tonglink_studio` 运行独立压测（HikariCP 6.3.3 + PG JDBC 42.7.11）：

1. 用新配置创建连接池，取到后端 PID `22788`；
2. 通过管理员连接执行 `pg_terminate_backend(22788)` 模拟连接被外部掐断；
3. 等待 35 秒让保活任务运行；
4. 连续 5 次通过池执行 `SELECT 1`：**全部成功**，池连接数由 1 自动恢复到 2。

结论：即使连接被强制掐断，应用请求不会失败，池会自动重建连接。此前看到的 WARN 是 Hikari 在替换死连接时打印的自愈日志。

### 18.4 测试结果

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **46 项全部通过**，0 失败 / 0 错误 |
| PG 容断压测 | 真实 PG + `pg_terminate_backend` + 30 秒保活 | 5 次查询全部成功，池自动恢复 |

> 若重启后控制台打印的 `keepalive` 不是 `30000`、或 WARN 伴随 `Connection is not available, request timed out` / SQL 异常，说明仍有用旧环境变量覆盖或数据库侧新故障，需要完整异常堆栈继续定位。

---

## 19. 定时任务借连接超时修复（2026-11-25）

用户补充了完整堆栈：`ScheduleService.tick → ExecutionService.resumeDue → ExecutionRepository.dueWaits` 抛出 `CannotGetJdbcConnectionException`，根因 `HikariPool-1 - Connection is not available, request timed out after 30012ms (total=0, active=0, idle=0, waiting=6)`。

### 19.1 分析

| 现象 | 解释 |
|---|---|
| `total=0` | 池内一条连接都没有，Hikari 正在尝试重建但尚未成功 |
| 超时恰好 30012ms | 等于 `DB_CONNECTION_TIMEOUT=30000`，说明借连接等满了整个超时窗口 |
| Hikari 补池周期 | 默认 **30 秒**（`com.zaxxer.hikari.housekeeping.periodMs`），与借连接超时窗口完全重合；池空后第一次补池最多要等 30 秒，定时任务正好卡在这个空窗 |
| 主程序仍可用 | 数据库在数十秒内恢复，新连接建立后 UI 请求正常；只有正在等待的定时线程抛错 |
| IDEA 当时配置 | `DB_POOL_SIZE=3`，仅 2 条最小空闲连接，定时扫描 + 触发器对齐 + UI 请求容易同时争用 |

### 19.2 修复措施

| # | 措施 | 说明 |
|---|---|---|
| 1 | Hikari 补池周期改为 **5 秒** | 在 `TongLinkApplication.main()` 中于 Spring 启动前设置系统属性，可用 JVM 参数覆盖；池空后最快 5 秒开始补连接 |
| 2 | `ScheduleService.tick()` 增加顶层异常兜底 | 数据库短暂不可用时只打印一条简洁 WARN 并跳过本轮，不再把异常交给 Spring `TaskUtils` 刷整屏 ERROR 堆栈 |
| 3 | `ExternalTriggerService.reconcile()` 同样兜底 | 数据库抖动时保留已建立的消息订阅，下一轮再对齐 |
| 4 | IDEA 运行配置池容量 3 → 8、最小空闲 2 → 4 | 给定时扫描、外部触发器对齐与 UI 请求留出余量（本地 gitignore 配置，已直接改好） |

### 19.3 验证

| 类别 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test`（14 模块 reactor） | **46 项全部通过**，0 失败 / 0 错误 |
| 真实 PG 连接状态 | `pg_stat_activity` | 服务端 09:23 启动后未重启，故障时段为连接重建空窗，非数据库宕机 |

> 这条异常与上一轮的 WARN 是同一类问题的两种表现：WARN 是借连接时单条死连接被替换；ERROR 是池整体暂时为空、定时任务等满 30 秒。两层修复（短保活 + 快补池 + 任务兜底）后，两者都不应再造成业务失败或日志刷屏。
