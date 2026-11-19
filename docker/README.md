# Data Khaos - Docker 部署

一键拉起**基础设施 + 全部 11 个业务服务**的容器编排。

## 目录

| 文件 | 说明 |
|------|------|
| `docker-compose.yml` | 编排：MySQL 8 + Redis 7 + Nacos + 11 服务 |
| `Dockerfile` | 通用多阶段构建（通过 `MODULE` 构建参数选服务） |
| `.env.example` | 环境变量模板 |
| `scripts/smoke-test.sh` | 启动冒烟/集成测试脚本 |

## 架构与端口

```
浏览器 / 前端 ──> 网关 :8099
                     │  路由 /api/auth  → auth      :8081
                     │        /api/permission → permission :8082
                     │        /api/approval → approval  :8083
                     │        /api/ds      → datasource :8084
                     │        /api/meta    → metadata  :8085
                     │        /api/mart    → mart      :8086
                     │        /api/query   → query     :8087
                     │        /api/visual  → visual    :8088
                     │        /api/schedule→ schedule  :8089
                     │        /api/notify  → notification :8090
```

服务间通过 **Nacos 注册发现**（`lb://` 负载均衡），JWT 由网关统一校验并向下游透传 `X-User-Id`。

## 快速开始

```bash
cd docker

# 1. 配置环境变量（可选，默认值见 .env.example）
cp .env.example .env

# 2. 构建镜像并启动（首次构建约 5~10 分钟）
docker compose up -d --build

# 3. 查看状态
docker compose ps

# 4. 运行冒烟测试（验证服务间调用链）
./scripts/smoke-test.sh

# 5. 查看日志
docker compose logs -f gateway auth datasource
```

## 常用操作

```bash
docker compose down            # 停止（保留数据卷）
docker compose down -v         # 停止并删除数据卷（重置数据库）
docker compose up -d auth      # 单独重启某服务
docker compose logs -f query   # 跟踪某服务日志
```

## 数据库

- 开发默认使用 **MySQL 8**，`../db/mysql-init.sql` 在首次启动时自动建库建表并写入种子数据（管理员 `admin` / 密码见下）。
- 新建 MySQL 数据卷会依次执行 `mysql-init.sql`、`migrate-workflow.sql`、`migrate-paas-pr1.sql`。使用已有数据卷时，先核对已执行的迁移，再按顺序补齐缺失脚本。`migrate-paas-pr1.sql` 中的加列操作只执行一次。
- 生产使用**达梦 DM8**：将 `.env` 中 `MYSQL_HOST/PORT/USERNAME/PASSWORD` 指向达梦实例，并配合 `mvn -Pprod` 构建（引入 `DmJdbcDriver18`）。

> 默认管理员账号：`admin`，密码：`password`（种子数据中的 BCrypt 哈希）。**生产环境务必修改。**

## 手动构建单个镜像

```bash
docker build --build-arg MODULE=data-khaos-gateway -t data-khaos/gateway .
```

## 说明

- 应用容器镜像基于 `eclipse-temurin:17-jre`，未内置 curl；因此应用本身不配 healthcheck，
  依赖 MySQL/Redis 的 `service_healthy` + `restart: on-failure` 保证拉起顺序与自动重试。
- Nacos 使用 `standalone` 单机模式，`NACOS_AUTH_ENABLE=false` 便于开发联调。
- 网关已对 `/api/query` 配置 Redis 限流（默认 10 req/s，可调）。
- **Apple Silicon (arm64)**：必须使用 `nacos/nacos-server:v2.4.3`（或更高），
  `v2.3.2` 及以下无 arm64 镜像，`docker compose up` 会报
  `no matching manifest for linux/arm64/v8`。
