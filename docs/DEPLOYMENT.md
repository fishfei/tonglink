# TongLink Studio 部署与运维指南

> 本文描述各部署形态的配置与操作步骤。工程代码生成后即可按此执行；文中镜像标签 `0.1.0` 为首个版本示例，实际以 Release 版本为准。
>
> 设计依据见 [`ARCHITECTURE.md`](ARCHITECTURE.md) 第 1.2 节与第 7 节。
>
> **模式可用性与镜像说明**：随代码产出的镜像 `dist/tonglink-studio-lite.tar` 默认以**轻量 lite 模式**启动（单容器 / SQLite / 进程内队列 / 内部 Runner），配套 `docker/docker-compose.lite.yml` 与 `scripts/deploy-lite.sh`。**PostgreSQL 已实际接通**：同一份 jar 内含 SQLite 与 PostgreSQL 两种 JDBC 驱动，通过 `DB_URL` 前缀自动切换，配套 `docker/docker-compose.postgres.yml` 可直接编排标准模式（已用真实 PostgreSQL 17 验证建表、读写与分页）。队列模式（Redis）、外部 Runner 仍属架构扩展目标，其 compose 示例用于说明扩展方式。环境变量名以第 8 节为准。

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

**切换原理**：应用只有一份 jar，内含 SQLite 与 PostgreSQL 两套 JDBC 驱动。数据源完全由环境变量决定——`DB_URL` 是唯一开关：

- 不设 `DB_URL`：默认 `jdbc:sqlite:./data/tonglink.db`（轻量模式）
- 设 `DB_URL=jdbc:postgresql://host:5432/db`：Spring Boot 依据 URL 前缀自动选择 PG 驱动，无需重新打包

配套变量：`DB_USER`、`DB_PASSWORD`、`DB_POOL_SIZE`（连接池大小，默认 5，PG 模式建议 20）。实测 PG 服务端（`idle_in_transaction_session_timeout=0`、`idle_session_timeout=0`）不会主动断开空闲连接，断链来自中间网络回收空闲 TCP 会话；因此连接池默认 `maxLifetime=5 分钟`、`keepaliveTime=30 秒`、`idleTimeout=2 分钟`、`validationTimeout=5 秒`，把「借到已断开连接」的窗口压到最小，分别可用 `DB_MAX_LIFETIME`、`DB_KEEPALIVE_TIME`、`DB_IDLE_TIMEOUT`、`DB_VALIDATION_TIMEOUT` 覆盖。`DB_INIT_FAIL_TIMEOUT=-1`（默认）表示数据库短暂不可用时应用仍启动、由 Hikari 后台持续重连。PG 的 `DB_URL` 建议追加 `?tcpKeepAlive=true&connectTimeout=5&socketTimeout=30`。启动时控制台会打印实际生效的池参数，便于确认 IDEA 运行配置没有用旧环境变量覆盖默认值。应用启动时还会把 Hikari 补池周期从默认 30 秒改为 5 秒（可用 JVM 参数 `-Dcom.zaxxer.hikari.housekeeping.periodMs` 覆盖），避免数据库抖动导致池空后正好卡满 30 秒借连接超时；定时调度与触发器对齐在数据库短暂不可用时只跳过本轮，不会刷出整屏 ERROR 堆栈。表结构由 `schema.sql` 在首次启动时自动创建（15 张表，SQLite/PG 通用）。项目不做旧库结构升级：结构变更请按最新脚本重建数据库并导入数据。

> 注意：旧版文档中出现的 `DB_TYPE`、`QUEUE_TYPE`、`STORAGE_DIR` 变量已被废弃，代码从未读取，请勿再使用。

启动：

```bash
docker compose up -d
docker compose ps
docker compose logs -f app
```

建议用 Nginx 反向代理 8080 并配置 HTTPS，不要把应用端口直接暴露到公网。

## 6. 队列模式（4+ 容器）

当出现以下情况时切换：执行任务堆积、定时触发互相阻塞、需要独立扩容执行能力、需要外部 Runner 隔离。

> 规划提示：队列模式（Redis + 多 Worker + 外部 Runner）仍属架构扩展目标，下方 compose 仅示意扩展方式，**当前镜像不能直接照搬运行**。数据库切换沿用第 5 节的 `DB_URL` 机制，队列相关变量（`QUEUE_TYPE` 等）当前版本不读取。需要高并发部署时请先按架构文档补齐对应实现。

`docker-compose.queue.yml` 内容（独立文件，不要与标准模式同时启动）：

```yaml
services:
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: tonglink
      POSTGRES_USER: tonglink
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pg_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tonglink -d tonglink"]
      interval: 10s
      retries: 5

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    volumes:
      - redis_data:/data

  app-web:
    image: tonglink-studio:0.1.0
    restart: unless-stopped
    ports:
      - "127.0.0.1:8080:8080"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: web
      DB_TYPE: postgres
      DB_URL: jdbc:postgresql://postgres:5432/tonglink
      DB_USER: tonglink
      DB_PASSWORD: ${DB_PASSWORD}
      QUEUE_TYPE: redis
      REDIS_HOST: redis
      REDIS_PORT: "6379"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started

  app-worker:
    image: tonglink-studio:0.1.0
    restart: unless-stopped
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: worker
      DB_TYPE: postgres
      DB_URL: jdbc:postgresql://postgres:5432/tonglink
      DB_USER: tonglink
      DB_PASSWORD: ${DB_PASSWORD}
      QUEUE_TYPE: redis
      REDIS_HOST: redis
      REDIS_PORT: "6379"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started

volumes:
  pg_data:
  redis_data:
  app_data:
```

启动与扩容：

```bash
docker compose -f docker-compose.queue.yml up -d
# 扩到 3 个 Worker
docker compose -f docker-compose.queue.yml up -d --scale app-worker=3
```

注意：所有进程（web / worker）必须使用**同一个 `ENCRYPTION_KEY`**，否则 Worker 无法解密数据源里的密码字段。

## 7. 外部 Runner 模式（可选增强）

默认的内部 Runner 是应用拉起的子进程，单容器即可用。若运行不可信代码或需要把代码执行的资源与主服务彻底分开，启用外部模式：

在队列模式的 compose 中增加：

```yaml
  runners:
    image: tonglink-runners:0.1.0
    restart: unless-stopped
    environment:
      RUNNER_MODE: external
      RUNNER_BROKER_HOST: app-web
      RUNNER_BROKER_PORT: "5679"
      RUNNER_BROKER_LISTEN_ADDRESS: 0.0.0.0
      RUNNER_AUTH_TOKEN: ${RUNNER_AUTH_TOKEN}
      RUNNER_TASK_TIMEOUT: "30"
      RUNNER_MAX_CONCURRENCY: "5"
      NODE_ALLOWED_BUILTINS: crypto,util
      PYTHON_STDLIB_ALLOW: json,datetime,re,math
    depends_on:
      - app-web
```

同时在 `app-web` / `app-worker` 环境里设置：

```bash
RUNNER_MODE=external
RUNNER_AUTH_TOKEN=与runners容器相同的值
RUNNER_BROKER_PORT=5679
```

要点：

- Runner 通过 WebSocket 连接应用内嵌的 TaskBroker（默认 5679），用 `RUNNER_AUTH_TOKEN` 鉴权。
- 队列模式下**每个 Worker 都要配一个 runner 容器**，版本必须与应用镜像匹配。
- 模块白名单要配在 **runner 容器**上，配在应用上无效——这是最容易踩的坑。
- 生产建议配合只读根文件系统、非 root 用户（uid 65532）、AppArmor 等加固。

## 8. 环境变量参考

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
| `TONGLINK_AI_PROVIDER` | `local` | `local`（本地规则引擎）/ `openai`（兼容接口） |
| `TONGLINK_AI_ENDPOINT` | 空 | OpenAI 兼容接口地址 |
| `TONGLINK_AI_API_KEY` | 空 | AI 接口密钥 |
| `TONGLINK_AI_MODEL` | `gpt-4o-mini` | AI 模型名 |
| `TZ` | `Asia/Shanghai` | 容器时区，影响定时触发 |

> 说明：队列模式（Redis）、外部 Runner、OTel 链路追踪仍属架构扩展目标，尚未接入镜像默认配置；PostgreSQL 标准模式已实际可用，按第 5 节操作即可。

## 9. 数据库与迁移

- **自动建表**：首次启动时由 Spring 的 `schema.sql` 创建 15 张表（SQLite / PostgreSQL 通用 DDL），无需手工执行 SQL 或依赖 Flyway。
- **结构升级**：不提供旧库迁移。按最新 `schema.sql` 建新库，再通过「数据源 / 流程导出」导入；旧版 `credential` 表不再存在，密码字段需在新环境重新录入（主密钥不同）。
- **从 lite 迁移到 standard**：在 lite 中导出 JSON，启动 standard 后逐项导入；数据源密码字段需要在新环境重新录入（主密钥不同）。
- **从 standard 升级到 queue**：数据库不变，只切换 profile 并增加 Redis 与 Worker，无需数据迁移。
- 执行日志与指标按保留策略自动裁剪（默认 30 天热数据），避免无限增长。

## 10. 备份与恢复

```bash
# PostgreSQL 模式
docker compose exec -T postgres pg_dump -U tonglink tonglink | gzip > tonglink-$(date +%F).sql.gz

# SQLite 模式（从命名卷复制数据库文件）
docker run --rm -v tonglink-data:/data -v "$PWD":/backup alpine \
  cp /data/tonglink.db /backup/tonglink-$(date +%F).db
```

恢复时同时需要：**数据库备份**、**`ENCRYPTION_KEY`**、**`STORAGE_DIR` 中的节点包与大对象**。三者缺一都会导致数据源密码字段不可用或数据不完整。请定期做一次恢复演练。

## 11. 监控与日志

- 健康检查：`curl http://localhost:8080/actuator/health`。
- 基础指标：`http://localhost:8080/actuator/metrics` 提供 JVM 与 HTTP 基础指标；当前仅暴露 `health` / `info` / `metrics`，Prometheus 端点与业务指标尚未启用。
- 应用日志输出到 stdout，用 `docker compose logs -f app` 查看；生产建议接入 Loki / Grafana。
- 全链路 traceId 与 OpenTelemetry 导出属可选增强，当前版本未接入。

## 12. 安全加固清单

- [ ] 修改唯一内置账号 admin 的默认密码。
- [ ] 自行设置 `JWT_SECRET` 与 `ENCRYPTION_KEY` 并离线备份。
- [ ] 应用端口只绑定 `127.0.0.1`，外网访问一律走反向代理 + HTTPS。
- [ ] 数据库端口不对外暴露。
- [ ] 运行不可信代码时启用外部 Runner，并配置模块白名单与只读文件系统。
- [ ] 固定镜像版本，不使用 `latest`；升级前先备份。
- [ ] 开启执行日志保留策略，避免长期存储敏感数据。

## 13. 故障排查

| 现象 | 排查方向 |
|---|---|
| 页面打不开 | `docker compose ps` 看容器状态；`logs app` 看启动异常；端口是否被占用 |
| 数据源密码显示解密失败 | `ENCRYPTION_KEY` 与保存密码时不一致，或备份恢复时漏了密钥 |
| 定时触发时间不对 | 检查 `TZ` 与容器的 `GENERIC_TIMEZONE` 是否一致 |
| 代码节点报 module is disallowed | 白名单要配在 runner（外部模式）上，不是应用上 |
| 队列模式任务不执行 | Worker 的 `QUEUE_TYPE` / `REDIS_HOST` 是否与 web 一致；Worker 是否与 web 共用 `ENCRYPTION_KEY` |
| SQLite 报 database is locked | 并发写入过多，切换到标准模式的 PostgreSQL |
| 外部 Runner 连不上 | `RUNNER_AUTH_TOKEN` 是否一致；`RUNNER_BROKER_HOST` 是否可达；5679 端口是否放行 |

---

如需进一步了解架构取舍，请回到 [`ARCHITECTURE.md`](ARCHITECTURE.md)；功能说明见 [`INTRODUCTION.md`](INTRODUCTION.md)。
