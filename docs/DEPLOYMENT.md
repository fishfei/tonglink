# TongLink Studio 部署与运维指南

> 本文描述各部署形态的配置与操作步骤。工程代码生成后即可按此执行；文中镜像标签 `0.1.0` 为首个版本示例，实际以 Release 版本为准。
>
> 设计依据见 [`ARCHITECTURE.md`](ARCHITECTURE.md) 第 1.2 节与第 7 节。
>
> **模式可用性与镜像说明**：随代码产出的镜像 `dist/tonglink-studio-lite.tar` 默认以**轻量 lite 模式**启动（单容器 / SQLite / 进程内队列 / 内部 Runner），配套 `docker/docker-compose.lite.yml` 与 `scripts/deploy-lite.sh`。**PostgreSQL 已实际接通**：同一份 jar 内含 SQLite 与 PostgreSQL 两种 JDBC 驱动，通过 `DB_URL` 前缀自动切换，配套 `docker/docker-compose.postgres.yml` 可直接编排标准模式（已用真实 PostgreSQL 17 验证建表、读写与分页）。队列模式（Redis）、外部 Runner 仍属架构扩展目标，其 compose 示例用于说明扩展方式。环境变量名以第 6 节为准。

---

## 1. 部署形态总览

容器数量取决于部署方式，不是固定的：

| 形态 | 容器数 | 组成 | 适用 |
|---|---|---|---|
| 轻量 lite | **1** | `app`（前端静态资源 + API + SQLite + 进程内队列 + 内部 Runner 子进程） | 试用、开发、简单工作流 |
| 标准 standard | **2** | `app` + `postgres:16-alpine` | 团队长期使用，推荐起点 |
| 队列 queue | **4+** | `app-web` + `app-worker` × N + `postgres` + `redis`；可选 `runners`、`nginx` | 高并发、水平扩展、强隔离 |
| Kubernetes | 弹性 | 队列模式容器化编排，Worker 按队列深度扩缩容 | 大规模生产 |

原则：**先用 lite 或 standard 跑起来，遇到瓶颈再拆**。从 SQLite 迁移到 PostgreSQL 比反过来容易得多，所以任何需要长期保留数据的场景建议直接选 standard。

## 2. 前置条件

- Docker 20.10+ 与 Docker Compose v2。
- 生产环境建议 2 vCPU / 4GB 内存 / 40GB SSD 起。
- 对外提供 HTTPS 时准备域名与反向代理（Nginx / Caddy / Traefik 任选）。
- 首次启动前生成并妥善保存两个密钥（丢失后数据源密码字段无法解密）：

```bash
# JWT 签名密钥
openssl rand -hex 32
# 凭据主密钥（TONGLINK_CRYPTO_KEY）
openssl rand -hex 32
```

> 请把生成的值存入密码管理器，不要提交到 Git。`.env` 必须在 `.gitignore` 中。

## 3. 构建镜像

本地**无需 Docker** 即可构建轻量模式镜像（Jib 直接生成 OCI tar）：

```bash
# 自动完成：前端构建 → 静态资源复制 → 后端打包 → Jib 构建镜像 tar
bash scripts/build-image.sh
# 产物：dist/tonglink-studio-lite.tar
```

在服务器上加载并启动：

```bash
docker load -i dist/tonglink-studio-lite.tar
docker compose -f docker/docker-compose.lite.yml up -d
# 或使用一键脚本：bash scripts/deploy-lite.sh dist/tonglink-studio-lite.tar
```

镜像内已包含 Java 21、Node.js 22 与 Python 3.11，单容器即可执行代码节点。

如果你的构建机已经安装 Docker，也可以先 `npm install && npm run build`、再把前端产物复制到 `backend/tonglink-app/src/main/resources/static/`，`mvn -DskipTests package` 后执行：

```bash
docker build -t tonglink-studio:0.1.0 -f docker/Dockerfile .
```

### 3.1 用 docker run 直接启动（SQLite / PostgreSQL）

不想用 Compose 时可直接 `docker run`。镜像已推送到华为云 SWR：`swr.cn-south-1.myhuaweicloud.com/fish/tonglink:0.1.0`，首次运行会自动拉取。镜像内置 `SPRING_PROFILES_ACTIVE=lite`、`TONGLINK_DB_PATH=/app/data/tonglink.db`，工作目录为 `/app`，因此把卷挂到 `/app/data` 即可持久化数据。

**方式一：SQLite（lite，单容器）**

```bash
docker run -d --name tonglink-studio \
  -p 8080:8080 \
  -v tonglink-data:/app/data \
  swr.cn-south-1.myhuaweicloud.com/fish/tonglink:0.1.0
```

不设环境变量时使用的是镜像内置的开发密钥。**生产环境务必追加密钥与时区**（否则 JWT 与数据源凭据沿用公开默认值）：

```bash
docker run -d --name tonglink-studio \
  -p 8080:8080 \
  -v tonglink-data:/app/data \
  -e TONGLINK_JWT_SECRET='替换为你的JWT密钥（足够长）' \
  -e TONGLINK_CRYPTO_KEY='替换为你的加密主密钥' \
  -e TZ=Asia/Shanghai \
  swr.cn-south-1.myhuaweicloud.com/fish/tonglink:0.1.0
```

打开 `http://服务器IP:8080` 即可访问。

**方式二：PostgreSQL（standard，需额外数据库容器）**

先建容器网络并启动 PostgreSQL（网络已存在时可跳过第一条）：

```bash
docker network create tonglink-net

docker run -d --name tonglink-postgres \
  --network tonglink-net \
  -e POSTGRES_DB=tonglink \
  -e POSTGRES_USER=tonglink \
  -e POSTGRES_PASSWORD='替换为数据库密码' \
  -v tonglink-pgdata:/var/lib/postgresql/data \
  postgres:16-alpine
```

再启动应用，通过 `DB_URL` 指向 PostgreSQL——同一份 jar，驱动按 URL 前缀自动选择，无需重新打包：

```bash
docker run -d --name tonglink-studio \
  --network tonglink-net \
  -p 8080:8080 \
  -v tonglink-data:/app/data \
  -e SPRING_PROFILES_ACTIVE=standard \
  -e DB_URL='jdbc:postgresql://tonglink-postgres:5432/tonglink?tcpKeepAlive=true&connectTimeout=5&socketTimeout=30' \
  -e DB_USER=tonglink \
  -e DB_PASSWORD='替换为数据库密码' \
  -e DB_POOL_SIZE=20 \
  -e TONGLINK_JWT_SECRET='替换为你的JWT密钥（足够长）' \
  -e TONGLINK_CRYPTO_KEY='替换为你的加密主密钥' \
  -e TZ=Asia/Shanghai \
  swr.cn-south-1.myhuaweicloud.com/fish/tonglink:0.1.0
```

表结构由 `schema.sql` 在首次启动时自动创建，无需手工建表。若希望用 Compose 编排（含健康检查与依赖顺序），见第 5 节。

## 4. 轻量模式（1 个容器）

一条命令启动，数据默认落在命名卷中：

```bash
docker run -d --name tonglink-studio \
  -p 8080:8080 \
  -v tonglink-data:/app/data \
  -e SPRING_PROFILES_ACTIVE=lite \
  -e TONGLINK_DB_PATH=/app/data/tonglink.db \
  -e TONGLINK_JWT_SECRET='替换为你的JWT密钥（足够长）' \
  -e TONGLINK_CRYPTO_KEY='替换为你的加密主密钥' \
  -e TZ=Asia/Shanghai \
  tonglink-studio:0.1.0
```

打开 `http://服务器IP:8080`，用内置账号登录（生产环境请立即修改默认密码）。

特点：

- SQLite 存元数据与执行记录，无需外部数据库。
- 执行队列在进程内，不需要 Redis。
- 代码节点由应用拉起的 Node.js / Python 子进程执行，不需要 runner 容器。
- 适合单用户或少量流程；并发写入较多时 SQLite 会成为瓶颈，请切换到标准模式。

## 5. 标准模式（2 个容器，PostgreSQL）

标准模式已实际接通并验证，直接使用随仓库提供的编排文件 `docker/docker-compose.postgres.yml`。

在仓库根目录准备 `.env`：

```bash
DB_PASSWORD=替换为数据库密码
DB_USER=tonglink
DB_NAME=tonglink
DB_POOL_SIZE=20
TONGLINK_JWT_SECRET=替换为你的JWT密钥
TONGLINK_CRYPTO_KEY=替换为你的主密钥
TZ=Asia/Shanghai
```

启动：

```bash
docker compose -f docker/docker-compose.postgres.yml up -d
```

启动：

```bash
docker compose up -d
docker compose ps
docker compose logs -f app
```

建议用 Nginx 反向代理 8080 并配置 HTTPS，不要把应用端口直接暴露到公网。

## 6. 环境变量参考

> 下表以 `backend/tonglink-app/src/main/resources/application.yml` 为准，是镜像实际读取的变量名。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `lite` | Spring 配置档位；轻量模式用 `lite`，标准模式用 `standard` |
| `SERVER_PORT` | `8080` | HTTP 与 WebSocket 共用端口 |
| `DB_URL` | 空 | **数据库开关**。留空用 SQLite；设为 `jdbc:postgresql://host:5432/db` 使用 PostgreSQL（驱动自动选择） |
| `DB_USER` | `sa` | 数据库用户名；PG 模式设为对应用户（如 `tonglink`） |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `DB_POOL_SIZE` | `5` | Hikari 连接池大小；PG 模式建议 `20` |
| `DB_MIN_IDLE` | `2` | 最小空闲连接数，避免空闲一段时间后冷连接集中重建 |
| `DB_MAX_LIFETIME` | `300000` | 连接最大存活毫秒数（5 分钟），应小于服务端/NAT 回收时间 |
| `DB_KEEPALIVE_TIME` | `30000` | Hikari 保活探测间隔（30 秒），必须小于 `DB_MAX_LIFETIME` |
| `DB_IDLE_TIMEOUT` | `120000` | 空闲连接回收时间（2 分钟），仅在空闲连接多于 `DB_MIN_IDLE` 时生效 |
| `DB_VALIDATION_TIMEOUT` | `5000` | 连接校验超时毫秒数 |
| `DB_INIT_FAIL_TIMEOUT` | `-1` | 数据库短暂不可达时仍启动，由 Hikari 后台持续重连 |
| `TONGLINK_DB_PATH` | `./data/tonglink.db` | 仅 SQLite 模式生效；容器内设为 `/app/data/tonglink.db` |
| `TONGLINK_CRYPTO_KEY` | 开发用默认值 | 数据源中认证凭据密码字段的 AES-GCM 主密钥（变量为明文，不依赖此密钥），**生产必须修改** |
| `TONGLINK_JWT_SECRET` | 开发用默认值 | JWT 签名密钥，**生产必须修改且足够长** |
| `TONGLINK_QUEUE_TYPE` | `inmemory` | `inmemory`（轻量/标准）/ `redis`（队列模式） |
| `TONGLINK_QUEUE_CONSUMERS` | `2` | 进程内队列的常驻消费者数，合法范围 1–32；单条流程仍串行执行 |
| `TONGLINK_QUEUE_CAPACITY` | `10000` | 进程内待执行队列容量（有界，避免无界队列 OOM）；满时按 `offer-timeout-ms` 等待后明确拒绝 |
| `TONGLINK_QUEUE_OFFER_TIMEOUT_MS` | `1000` | 队列满时入队等待毫秒数，`0` 表示立即拒绝；被拒的执行会标记为 FAILED 而不是卡在 PENDING |
| `TONGLINK_REDIS_URI` | `redis://localhost:6379` | 仅队列模式生效 |
| `TONGLINK_REDIS_CONSUMERS` | `2` | Redis 队列的常驻消费者数，合法范围 1–64；每个消费者使用独立连接做阻塞读 |
| `TONGLINK_REDIS_BLOCK_MS` | `2000` | Redis 阻塞读时长，最小 100ms |
| `TONGLINK_REDIS_BATCH_SIZE` | `10` | 每次读取/认领的消息条数，合法范围 1–100 |
| `TONGLINK_REDIS_CLAIM_IDLE_MS` | `300000` | PEL 中消息超过该空闲时间后才认领重投，最小 10 秒；应大于正常执行时长 |
| `TONGLINK_REDIS_CLAIM_INTERVAL_MS` | `60000` | 认领检查间隔，最小 5 秒；只由 0 号消费者执行 |
| `TONGLINK_NODE_CMD` | `node` | Node.js 可执行文件（代码节点） |
| `TONGLINK_PYTHON_CMD` | `python3` | Python 可执行文件（代码节点） |
| `TONGLINK_PYTHON_FALLBACK` | `python` | Python 命令回退名 |
| `TONGLINK_AI_PROVIDER` | `local` | AI 引擎；运行时优先读「AI 设置」页保存的配置，未配置时才用这里的值。可选 `local` / `deepseek` / `zhipu` / `qwen` / `hunyuan` / `custom` |
| `TONGLINK_AI_ENDPOINT` | 空 | OpenAI 兼容 Chat Completions 完整地址（未使用 AI 设置页时生效） |
| `TONGLINK_AI_API_KEY` | 空 | AI 接口密钥 |
| `TONGLINK_AI_MODEL` | `gpt-4o-mini` | AI 模型名 |
| `TZ` | `Asia/Shanghai` | 容器时区，影响定时触发 |

> 说明：队列模式（Redis）、外部 Runner、OTel 链路追踪仍属架构扩展目标，尚未接入镜像默认配置；PostgreSQL 标准模式已实际可用，按第 5 节操作即可。

## 7. 数据库

- **自动建表**：首次启动时由 Spring 的 `schema.sql` 创建 15 张表（SQLite / PostgreSQL 通用 DDL），无需手工执行 SQL 或依赖 Flyway。
- 执行日志与指标按保留策略自动裁剪（默认 30 天热数据），避免无限增长。

## 8. 备份与恢复

```bash
# PostgreSQL 模式
docker compose exec -T postgres pg_dump -U tonglink tonglink | gzip > tonglink-$(date +%F).sql.gz

# SQLite 模式（从命名卷复制数据库文件）
docker run --rm -v tonglink-data:/data -v "$PWD":/backup alpine \
  cp /data/tonglink.db /backup/tonglink-$(date +%F).db
```

恢复时同时需要：**数据库备份**与**`TONGLINK_CRYPTO_KEY`**（缺失会导致数据源密码字段无法解密）。请定期做一次恢复演练。

## 9. 监控与日志

- 健康检查：`curl http://localhost:8080/actuator/health`。
- 基础指标：`http://localhost:8080/actuator/metrics` 提供 JVM 与 HTTP 基础指标；当前仅暴露 `health` / `info` / `metrics`，Prometheus 端点与业务指标尚未启用。
- 应用日志输出到 stdout，用 `docker compose logs -f app` 查看；生产建议接入 Loki / Grafana。
- 全链路 traceId 与 OpenTelemetry 导出属可选增强，当前版本未接入。

## 10. 安全加固清单

- [ ] 修改唯一内置账号 admin 的默认密码。
- [ ] 自行设置 `TONGLINK_JWT_SECRET` 与 `TONGLINK_CRYPTO_KEY` 并离线备份。
- [ ] 应用端口只绑定 `127.0.0.1`，外网访问一律走反向代理 + HTTPS。
- [ ] 数据库端口不对外暴露。
- [ ] 运行不可信代码时启用外部 Runner，并配置模块白名单与只读文件系统。
- [ ] 固定镜像版本，不使用 `latest`；升级前先备份。
- [ ] 开启执行日志保留策略，避免长期存储敏感数据。

## 11. 故障排查

| 现象 | 排查方向 |
|---|---|
| 页面打不开 | `docker compose ps` 看容器状态；`logs app` 看启动异常；端口是否被占用 |
| 数据源密码显示解密失败 | `TONGLINK_CRYPTO_KEY` 与保存密码时不一致，或备份恢复时漏了密钥 |
| 定时触发时间不对 | 检查 `TZ` 与容器的 `GENERIC_TIMEZONE` 是否一致 |
| 代码节点报 module is disallowed | 白名单要配在 runner（外部模式）上，不是应用上 |
| 队列模式任务不执行 | Worker 的 `QUEUE_TYPE` / `REDIS_HOST` 是否与 web 一致；Worker 是否与 web 共用 `TONGLINK_CRYPTO_KEY` |
| SQLite 报 database is locked | 并发写入过多，切换到标准模式的 PostgreSQL |
| 外部 Runner 连不上 | `RUNNER_AUTH_TOKEN` 是否一致；`RUNNER_BROKER_HOST` 是否可达；5679 端口是否放行 |

---

如需进一步了解架构取舍，请回到 [`ARCHITECTURE.md`](ARCHITECTURE.md)；功能说明见 [`INTRODUCTION.md`](INTRODUCTION.md)。
