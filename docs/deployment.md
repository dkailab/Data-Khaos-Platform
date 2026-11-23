# Data Khaos 部署运维文档

## 1. 部署架构

Data Khaos 共 11 个可运行服务 + 3 个基础设施组件。开发环境通过 Docker Compose 一键部署。

| 组件 | 容器名 | 端口 | 说明 |
|------|--------|------|------|
| MySQL 8 | dk-mysql | 3306 | 业务库（开发默认），自动执行 `db/mysql-init.sql` |
| Redis 7 | dk-redis | 6379 | 网关限流、会话/验证码缓存 |
| Nacos | dk-nacos | 8848 / 9848 | 服务注册与发现（standalone） |
| 网关 | dk-gateway | 8099 | 统一入口 + JWT 鉴权 + 路由 |
| 认证中心 | dk-auth | 8081 | 登录 / SSO / 验证码 / 用户 / 角色 |
| 权限服务 | dk-permission | 8082 | RBAC、行/列/表权限、组织 |
| 审批服务 | dk-approval | 8083 | 权限申请 / 审批流 |
| 数据源服务 | dk-datasource | 8084 | 数据源接入（8 种连接器） |
| 元数据中心 | dk-metadata | 8085 | 元数据采集 / 血缘 / 搜索 |
| 数据集市 | dk-mart | 8086 | 模型 / 指标 / 维度 |
| SQL 查询平台 | dk-query | 8087（容器内） | 通过网关访问在线查询和 SQL 审核 |
| 可视化引擎 | dk-visual | 8088 | 仪表板 / 分析板 |
| 调度系统 | dk-schedule | 8089 | 定时任务 / DAG / 重试 |
| 推送系统 | dk-notification | 8090（容器内） | 通过网关访问模板、通知和平台监控 |

## 本机快速验证

准备 JDK 17、Maven、Node.js 和前端依赖（`npm --prefix data-khaos-web ci`）。下面的入口并行执行后端测试和前端生产构建，日志保存在命令输出的临时目录。

```bash
# 提交前验证全部模块
bash scripts/test-fast.sh

# 只修改了 Flink 开发相关代码
bash scripts/test-fast.sh data-khaos-workflow,data-khaos-permission

# 首次缺少 Maven 依赖时允许下载
bash scripts/test-fast.sh --online
```

依赖和接口逻辑用本机测试验证。Flink、Kafka 或跨服务行为需要引擎验收时，先用 Maven 按模块打包 JAR，再使用已有容器镜像启动所需依赖。实时数仓样例见[订单链路](paas/realtime-warehouse.md)。

## 2. 快速部署（Docker Compose）

### 2.1 环境要求

- Docker 20.10+ / Docker Compose v2
- 主机内存 ≥ 8GB（Nacos + MySQL + 11 个 JVM 服务）

### 2.2 部署步骤

```bash
cd docker
cp .env.example .env          # 按需修改密码/密钥
docker compose up -d --build  # 首次构建约 5~10 分钟
docker compose ps             # 等待所有服务 running
./scripts/smoke-test.sh       # 冒烟测试，全部通过 ✔
```

### 2.3 验证

- 网关就绪：`curl http://localhost:8099/api/auth/captcha` 返回 `"code":0`
- 登录：`curl -X POST -H 'Content-Type: application/json' -d '{"username":"admin","password":"password"}' http://localhost:8099/api/auth/login`
- 服务注册：浏览器打开 `http://localhost:8848/nacos`（默认无鉴权）查看 11 个服务是否全部注册
- 接口文档（网关下发的各服务 Knife4j）：`http://localhost:8099/doc.html`

### 2.4 补数批次与分区追溯升级

已有 MySQL 数据卷先执行 `db/migrate-workflow-backfill-batch.sql`，再执行 `db/migrate-pipeline-publication.sql` 和 `db/migrate-paas-pr3.sql`。已有环境须先完成 PR 2A、2B、2C 的迁移。新卷由 Compose 按顺序执行。发布实例列迁移只执行一次，PR 3 脚本会刷新示例看板和资产配置。

补数重试使用原键、原区间和原参数。历史补数请求从原来的按日运行中恢复批次信息，已有歧义的历史键需要换新键。数据地图的资产详情可按业务日查看发布记录和运行，示例看板可从业务日期打开相同的证据页。当前追溯覆盖 MySQL DB-Sync 的 InnoDB 按日覆盖，旧运行缺少写入目标快照，重算后才会出现发布证据。示例使用 `ds_dw` 与 `ds_app` 两个数据源配置，均指向平台 MySQL 的 `data_khaos` 库。目标提交后由管道服务确认发布。若进程在这两步之间中断，先核对目标分区写入状态，再按原业务日期重跑。

### 2.5 查询任务中心与本地验收

从 **数据开发 → SQL 开发编辑器** 提交后台查询。任务列表支持筛选、取消排队、停止接收运行结果、恢复 SQL 和查看结果。运行中的取消保留数据库请求，服务收到结果后丢弃它。同步执行和 CSV 仍走工作台的现有入口。

`POST /api/query/jobs` 接收 `requestId`（UUID）、`datasourceId`、可选的 `databaseName` 和一条普通 `SELECT`。相同账号和请求标识重复提交返回原任务，改动参数后需用新标识。用 `GET /api/query/jobs` 分页筛选，`GET /api/query/jobs/{id}` 读取状态，`GET /api/query/jobs/{id}/result` 读取成功结果，`POST /api/query/jobs/{id}/cancel` 取消。所有接口按当前登录用户访问其个人任务。

每个查询服务实例同时运行最多两个后台任务。等待超过 15 分钟的任务标记失败。执行中断后，超过 10 分钟的运行标记失败并可手动重提。结果沿用数据源的返回行数限制，任务最多留存 1 MiB 的结果，保留 24 小时。任务记录保留 30 天。

#### 查询用量

在查询任务栏展开「查询用量」，可查看最近 7、14 或 30 天的后台查询趋势。选择日期后任务列表按当日提交时间筛选，可以继续按状态过滤。空白日期仍保留在趋势里。统计按查询服务的本地日历，以提交日归组并读取任务的当前状态，成功任务的平均执行耗时只用有完整起止时间的记录，排除排队时间。

`GET /api/query/jobs/usage?days=7` 返回按日期升序排列的每日状态计数、成功任务计时样本数和执行总秒数。`days` 接受 1 至 30，含当天。`GET /api/query/jobs?submittedOn=YYYY-MM-DD` 返回同一日期口径的任务明细，支持原有的状态筛选和分页。两个接口都按登录账号隔离，复用查询任务表与已有索引。上线沿用查询任务中心的迁移。同步查询、引擎扫描量、资源账单与团队归因需要独立采集。

在仓库根目录执行下面的模块构建和前端编译。Java 测试自带内存库，首次准备依赖时去掉 `-o`。

```bash
mvn -o -pl data-khaos-query -am package
npm --prefix data-khaos-web run build
```

已有可用的 Compose 查询镜像时，可以把本地打包的 JAR 挂载到查询容器。已有数据卷先执行 `db/migrate-query-jobs.sql`，新建 MySQL 数据卷由 Compose 自动执行。下面的命令在仓库根目录运行。

```bash
docker compose -f docker/docker-compose.yml exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" data_khaos' < db/migrate-query-jobs.sql

docker compose -f docker/docker-compose.yml -f docker/compose.query-dev.yml \
  up -d --no-build --no-deps --force-recreate query
```

后续迭代重复打包和重建 query 容器即可。联调结束后，用基础 Compose 文件重新创建 query 容器可恢复镜像内的 JAR。

### 2.6 平台监控告警

以平台管理员登录，进入 **监控运维 → 平台监控告警**。服务状态基于 Nacos 注册信息和 TCP 连通性，页面显示最近探测时间。连续三次出现实例缺失或不可达时生成告警与站内通知，连续两次完全可达时记录恢复并通知。注册中心查询异常时标记为未知，同一采样时间窗口内只计一次。管理员确认告警会保存确认时间，恢复由探测结果决定。首版覆盖 10 个 PaaS 服务的网络可用性，任务运行、引擎就绪、资源指标及邮件告警另行接入。

现有 MySQL 数据卷需执行 `db/migrate-platform-monitor.sql`。新建 Compose 数据卷会自动执行。通知服务与网关使用相同的 `JWT_SECRET`，探测可以通过 `PLATFORM_MONITOR_ENABLED=false` 暂停。

```bash
mvn -o -pl data-khaos-notification -am package
npm --prefix data-khaos-web run build
```

后端测试用内存数据库检查采样去重、恢复、告警与通知的事务一致性，以及管理员鉴权。平台监控首版的数据库验收目标为 MySQL，达梦环境待适配。

## 3. 生产部署（达梦 DM8）

### 3.1 差异点

| 维度 | 开发 | 生产 |
|------|------|------|
| 数据库 | MySQL 8（application.yml 默认） | 达梦 DM8 |
| 构建 | `mvn clean install` | `mvn clean install -Pprod`（引入 `DmJdbcDriver18`） |
| 连接配置 | `MYSQL_HOST/...` 环境变量 | 指向 DM8 实例的 JDBC 连接 |
| 密钥 | 默认值 | 必须更换 `JWT_SECRET` / `AES_KEY` |

### 3.2 达梦连接示例

```yaml
spring:
  datasource:
    driver-class-name: dm.jdbc.driver.DmDriver
    url: jdbc:dm://<dm8-host>:5236/DATA_KHAOS
    username: data_khaos
    password: <强密码>
```

> 达梦初始化脚本见 `db/init.sql`；主键采用雪花算法 `VARCHAR(32)`，避免达梦自增序列性能问题。

## 4. 运维手册

### 4.1 常用命令

```bash
# 查看全部服务状态
docker compose ps

# 跟踪某服务日志
docker compose logs -f -t <service>

# 重启单个服务（改配置后）
docker compose up -d <service>

# 全部停止（保留数据）
docker compose down

# 全部停止并重置数据（重建数据库）
docker compose down -v

# 健康检查：服务进程
docker exec dk-gateway sh -c 'jps'
```

### 4.2 故障排查

| 症状 | 排查步骤 |
|------|----------|
| 服务反复重启（restarting） | `docker compose logs <svc>`，多为数据库/Nacos 未就绪，等待 infra 健康后再拉起 |
| 登录失败 / 401 | 确认 MySQL 种子数据已初始化（admin 存在）；确认 `JWT_SECRET` 各服务一致 |
| 服务注册不上 | 确认 `NACOS_ADDR` 指向 `nacos:8848`；`nc -zv nacos 8848` 可通；查看 nacos 控制台 |
| 网关 503 | 目标服务未注册到 Nacos，检查对应服务日志 |
| 限流触发 429 | `/api/query` 默认 10 req/s，调大 `replenishRate` 或确认 Redis 连通 |
| 时区错乱 | 镜像已内置 `-Duser.timezone=Asia/Shanghai`，如需调整改 `docker/Dockerfile` 的 `JAVA_OPTS` |

### 4.3 数据备份

- MySQL：`docker exec dk-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > backup.sql`
- Nacos 配置：Nacos 数据挂载在 `nacos-data` 卷，备份该卷即可。

## 5. 安全加固清单（上线前）

- [ ] 更换 `JWT_SECRET`、`AES_KEY`（`.env` / 环境变量），长度 ≥ 32 字符
- [ ] 修改 MySQL root 密码与默认管理员 `admin` 密码
- [ ] 启用 Nacos 鉴权（`NACOS_AUTH_ENABLE=true` + 配置用户名密码）
- [ ] 网关白名单仅保留 `/api/auth/login`、`/api/auth/captcha`、接口文档
- [ ] 数据源密码字段已 AES 加密落库，确认 `AES_KEY` 托管于密钥管理服务
- [ ] 生产启用达梦 DM8 + `mvn -Pprod` 构建
