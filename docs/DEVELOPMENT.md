# 本地开发指南

面向使用 IntelliJ IDEA / VS Code 的开发者。项目采用 **Monorepo**：`backend/` 是 Spring Boot 多模块工程，`frontend/` 是 Vue 3 + Vite 工程，`prototype/` 保留可交互原型作为交互基线。

## 1. 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 LTS | `backend/pom.xml` 已锁定 `release=21` |
| Maven | 3.9+ | 也可用 IDEA 内置 Maven |
| Node.js | 20+ | 推荐 22 LTS |
| npm | 10+ | 前端依赖安装 |

请自行安装以上工具链（JDK 21、Maven 3.9、Node.js 20+）并加入 `PATH`。在 IDEA 中配置：
- `File → Project Structure → SDKs → + → JDK`，选择已安装的 JDK 21
- `File → Project Structure → Project → SDK`，选择刚添加的 JDK 21（语言级别 21）
- `Settings → Build Tools → Maven → Maven home path`，选择已安装的 Maven 3.9
- `Settings → Build Tools → Maven → Runner → JRE`，同样选择 JDK 21（**不要用默认的 1.8**）

> **常见错误：`java: 无效的目标发行版: 21`**  
> 原因是 IDEA 实际用来编译的 JDK 版本低于 21（常见为 1.8），而 `pom.xml` 里 `maven.compiler.release=21`。请按上面四步把 **Project SDK** 和 **Maven Runner JRE** 都设为 JDK 21，然后 `File → Invalidate Caches / Restart` 重启 IDEA；命令行执行 `java -version` 与 `mvn -version` 显示的运行时都应是 21。项目本身从未改成低版本 JDK，这个报错只与本机 IDE 的 JDK 设置有关。

## 2. 用 IDEA 打开

1. `File → Open`，选择仓库根目录下的 `backend/pom.xml`，以 Maven 项目方式打开。
2. 等待依赖下载完成，右侧 Maven 面板可看到 15 个项目（父工程 + 14 模块）。
3. 运行配置：`main class = cn.tonglink.app.TongLinkApplication`，工作目录设为 `backend/tonglink-app`。
4. 默认使用轻量模式：`jdbc:sqlite:./data/tonglink.db`，首次启动自动建表并写入示例数据。
5. 启动后访问 `http://localhost:8080`，API 文档在 `http://localhost:8080/swagger-ui.html`。

## 3. 前端开发

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173，自动代理 /api 到 8080
npm run test       # Vitest 单元测试
npm run build      # 类型检查 + 生产构建，产物在 frontend/dist
```

生产构建后把 `frontend/dist/*` 复制到 `backend/tonglink-app/src/main/resources/static/`，再重新打包后端即可由 Spring Boot 单进程托管前端。

## 4. 后端测试

```bash
cd backend
mvn test                    # 全部单元测试与集成测试
mvn -pl tonglink-app test   # 仅端到端集成测试
```

测试用例覆盖：DAG 环检测、节点 Schema 加载、Node.js/Python 代码执行、登录鉴权、流程执行到日志与看板的完整链路、AI 流程生成。

## 5. 构建轻量镜像

本机**不需要 Docker** 即可生成 OCI 镜像 tar：

```bash
bash scripts/build-image.sh
# 产物：dist/tonglink-studio-lite.tar
```

在服务器上：

```bash
bash scripts/deploy-lite.sh dist/tonglink-studio-lite.tar
```

或手动：

```bash
docker load -i dist/tonglink-studio-lite.tar
docker compose -f docker/docker-compose.lite.yml up -d
```

镜像内已包含 Java 21、Node.js 22 与 Python 3.11，因此轻量模式单容器即可运行代码节点。

## 6. 代码规范

- 全部注释使用中文；公开类/方法必须有 Javadoc（Java）或 JSDoc（TypeScript）。
- 每个 Maven 模块有独立 `README.md` 说明职责与依赖方向。
- 流程 JSON 与节点 Schema 是前后端唯一契约，修改需同步 `prototype/data.js`、`backend/tonglink-node/src/main/resources/node-definitions.json` 与前端类型。
- 依赖变更需确认许可证为 MIT / Apache-2.0 等宽松协议。

## 7. 常用配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `TONGLINK_DB_PATH` | `./data/tonglink.db` | SQLite 数据库路径 |
| `TONGLINK_CRYPTO_KEY` | 开发用默认值 | 数据源密码字段 AES-GCM 主密钥，生产必须修改 |
| `TONGLINK_JWT_SECRET` | 开发用默认值 | JWT 签名密钥，生产必须修改 |
| `TONGLINK_QUEUE_TYPE` | `inmemory` | 切换 `redis` 进入队列模式 |
| `TONGLINK_NODE_CMD` | `node` | Node 代码运行时命令 |
| `TONGLINK_PYTHON_CMD` | `python3` | Python 代码运行时命令 |
