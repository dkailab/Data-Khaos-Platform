# Data-Khaos 数据管道服务（Data Pipeline）架构与实现规划

> 版本：1.0.0
> 适用模块：`data-khaos-pipeline`（管理面）+ `data-khaos-pipeline-worker`（执行面）
> 状态：规划 + 最小闭环实现

- [1. 概述](#1-概述)
- [2. 双层架构](#2-双层架构平台--引擎)
- [3. 引擎可扩展设计](#3-引擎可扩展设计spi-适配器)
- [4. 数据模型设计](#4-数据模型设计)
- [5. API 设计](#5-api-设计)
- [6. 权限模型](#6-权限模型)
- [7. 部署与运维](#7-部署与运维)
- [8. 路线图](#8-路线图)

---

## 1. 概述

数据管道服务负责**异构数据源之间的数据搬运、同步与加工**，是数据治理平台「数据接入」能力落地的核心执行枢纽。

核心目标：

- **统一任务管理**：任务定义、手动/定时触发、执行实例、运行日志、状态监控，全部纳入平台统一门户与权限体系。
- **引擎可扩展**：底层执行引擎通过 SPI 适配器模式抽象，当前内置 **SeaTunnel（Waterdrop）**、**DataX** 两个开源引擎，并提供 **DB-Sync** 内置兜底引擎保证最小闭环可用；后续可平滑扩展 Flink / Spark / Debezium CDC 等。
- **平台与执行解耦**：管理面（pipeline 服务）负责任务编排与状态入库；执行面（worker / 引擎进程）只做数据搬运，崩溃自愈，不影响主平台。

---

## 2. 双层架构（平台 + 引擎）

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        管理面 · 现有平台微服务群                            │
│                                                                          │
│   ┌──────────┐    ┌──────────────┐    ┌─────────────┐    ┌──────────┐    │
│   │ 网关/门户 │──▶│  pipeline 服务 │──▶│  datasource  │    │  schedule │    │
│   │  (8099)  │    │    (8092)    │    │   (8084)    │    │  (8089)  │    │
│   └──────────┘    └──────┬───────┘    └─────────────┘    └──────────┘    │
│                          │ 任务/实例/日志/worker 注册（MySQL）              │
│                          ▼                                                │
│                 权限能力位 pipeline:manage / browse / run                   │
└──────────────────────────────────────────┬───────────────────────────────┘
                                            │ 派发执行（HTTP / 进程调用）
┌──────────────────────────────────────────▼───────────────────────────────┐
│                        执行面 · 独立 worker 容器                           │
│                                                                          │
│   ┌─────────────────────────────┐                                        │
│   │  pipeline-worker（Python）   │                                        │
│   │  ┌────────────┐ ┌─────────┐ │                                        │
│   │  │ SeaTunnel   │ │  DataX  │ │   （引擎适配器：配置生成 + 进程执行）      │
│   │  │  Engine     │ │  Engine │ │                                        │
│   │  └────────────┘ └─────────┘ │                                        │
│   └─────────────────────────────┘                                        │
└───────────────────────────────────────────────────────────┬──────────────┘
                                                            │ 读写
                                            ┌───────────────▼──────────────┐
                                            │  异构数据源：MySQL/达梦/        │
                                            │  Hive/Doris/ClickHouse/文件    │
                                            └──────────────────────────────┘
```

### 职责边界

| 层 | 职责 | 技术选型 |
|---|---|---|
| **管理面** pipeline 服务 | 任务 CRUD、实例管理、日志、worker 注册、引擎配置生成、调度触发、权限 | Spring Cloud + MyBatis-Plus（复用现有微服务模板，端口 8092） |
| **执行面** pipeline-worker | 拉取任务、执行引擎、回传状态/进度/日志 | Python + 引擎 CLI（SeaTunnel / DataX），独立容器 |
| **兜底引擎** DB-Sync | 不依赖引擎二进制的 JDBC 直连同步，保证最小闭环可用 | Java（pipeline 服务内，通过 datasource API） |

### 为什么这样分

- **复用**：pipeline 服务复用统一权限、统一数据源管理（datasource API）、统一网关与门户，门户 `数据同步任务` 入口直接挂载。
- **隔离**：引擎执行重 IO、易崩溃，独立进程/容器不影响主平台；任务超时、OOM 自愈。
- **可扩展**：新增引擎只需实现 SPI 适配器 + worker 内对应 CLI，无侵入。

---

## 3. 引擎可扩展设计（SPI 适配器）

### 引擎抽象

```java
/**
 * 管道执行引擎 SPI。新增引擎：实现本接口 + 在 EngineFactory 注册即可。
 */
public interface PipelineEngine {
    /** 引擎标识：DB_SYNC / DATAX / SEATUNNEL（新增在此扩展） */
    String type();

    /** 引擎是否可用（例如引擎 CLI 是否安装） */
    boolean available();

    /** 由任务定义生成引擎运行配置（JSON/命令参数） */
    String buildRunConfig(PipelineTask task);

    /** 同步执行一次任务，返回影响行数；抛异常表示失败 */
    int execute(PipelineTask task, PipelineInstance instance) throws Exception;
}
```

### 内置引擎

| 引擎 | type | 说明 | 可用性 |
|---|---|---|---|
| **DB-Sync（兜底）** | `DB_SYNC` | pipeline 服务内通过 datasource API 读源表 → 写目标表，真实可跑，不依赖引擎二进制 | 始终可用 |
| **DataX** | `DATAX` | 阿里开源离线同步，生成 `job.json`，通过 ProcessBuilder 调用 `python datax.py` | 需 worker/宿主机安装 DataX |
| **SeaTunnel（Waterdrop）** | `SEATUNNEL` | Apache 开源，支持流/批，生成 conf 文件并调用 `start-seatunnel.sh` | 需 worker/宿主机安装 SeaTunnel |

> **扩展点**：新增 Flink / Spark / CDC 引擎时，只需添加对应 `PipelineEngine` 实现并在 `EngineFactory` 注册，前端 `引擎列表` 接口自动返回新引擎，无需改动主流程。

#### DB-Sync（兜底，最小闭环真实可跑）
通过 JDBC 直连源/目标数据源完成同步，不依赖任何引擎二进制，**pipeline 服务内即可运行**（真实可跑，已通过端到端验证）。

#### Hive 到关系型数据库的加载

在 **数据接入 → 数据同步任务** 选择 Hive 源、MySQL 或达梦 DM8 目标和 DB-Sync。HiveServer2 执行源查询，任务按批读取与写入。Hive 连接沿用默认认证，也可在数据源选择 NOSASL。接收方先建目标表，SQL 使用源库只读账号，目标账号需具备目标表查询、插入及覆盖所需的删除权限。MySQL 目标使用 InnoDB，达梦使用数据源的模式名称，可填写相同模式下的 `模式.表`。普通未加引号创建的达梦对象按大写名称查找。

| 参数 | 约定 |
|---|---|
| `writeMode` | `APPEND` 追加，`REPLACE_BY_BIZ_DATE` 按业务日覆盖。后者由每日工作流传入 `bizDate`，也可在运行弹窗手工选择。 |
| `dateColumn` | 按日覆盖时填写目标日期列，支持日期、时间戳或 ISO 日期字符串列。 |
| `sourceQuery` | 可选单条 SELECT。覆盖模式的自定义 SQL 使用 `${bizDate}` 占位。省略 SQL 时自动按源日期列查询。 |
| `fieldMapping` | 源列到目标列的 JSON，例如 `{"dt":"stat_date"}`。未映射字段按同名匹配，映射到不存在或重复的字段会被拒绝。 |
| `config` | `sourceDateColumn` 可指定区别于目标的源日期列；`batchSize` 默认 500，取 1 至 5000；`timeoutSeconds` 默认 600，取 1 至 3600。 |

加载前检查字段及日期列。按日覆盖把当日删除与所有插入放在同一目标事务，跨日行或批次写入失败会回滚。成功读到零行会提交空分区。执行实例返回写入行数、目标库或模式、目标表及业务日期。MySQL 按日覆盖继续生成发布证据。达梦按日覆盖在目标表上使用事务锁，适合为下游系统准备日批结果。提交应答丢失时，实例状态为提交待核查。工作流保留业务日锁，调度不自动重试。独立手工任务重跑前需要核对目标结果。

验收包含空分区、字段映射、精度、中文和 NULL、跨日异常、批次失败、同日重跑和 MySQL 发布证据。达梦上线前在目标实例验证连接、SELECT/DELETE/INSERT、事务锁和失败回滚。

#### 上线前预检

在已保存的 Hive DB-Sync 任务点 **预检**。覆盖模式或引用业务日参数的查询需要一个业务日期。接口为 `POST /api/pipeline/task/{id}/preflight`，请求体为 `{"bizDate":"YYYY-MM-DD"}`。操作者需具备该项目的管道运行权限。

预检用与正式运行相同的 Hive 查询、目标基础表校验、字段映射和日期列校验。Hive JDBC 最多返回一行用于检查业务日，平台响应只包含目标表、批大小、字段类型、样例存在性与风险提示。目标只读元数据，无任务实例、事务写入和分区锁。无样例仍可通过，覆盖模式会提示实际为空时将清理当日。数值精度或小数位不足作为风险提示，实际目标 DML 权限、完整源数据与服务端查询开销需通过正式运行验证。

验收覆盖按日参数、映射冲突、目标列变化、样例跨日、空分区、精度提示、项目权限，以及无目标数据变更和无新增运行实例。

#### DataX / SeaTunnel（可插拔引擎）
pipeline 服务内的引擎适配器会**生成标准的 DataX job.json / SeaTunnel conf 文件**，并通过 `ProcessBuilder` 调用引擎 CLI（`python datax.py` / `start-seatunnel.sh`）以独立子进程执行。引擎二进制只需安装在宿主机/pipeline 容器并配置 `DATAX_HOME` / `SEATUNNEL_HOME` 环境变量即可无缝启用；未安装时 `available()` 返回 `false`，前端「引擎列表」明确标识「未安装」。

> **执行面形态**：既支持「引擎子进程」方式（进程级隔离，崩溃自愈），也支持后续演进为独立 worker 容器（Python/引擎 CLI）。当前实现采用前者，简化部署、复用服务内数据源凭证。

### 执行流程（DB-Sync 兜底，最小闭环）

```
触发运行（手动/定时）
   │
   ▼
创建 PipelineInstance（status=0 运行中）
   │
   ▼
PipelineRunner 提交到线程池
   │
   ├─▶ engine = EngineFactory.get(task.engine)
   │
   ├─▶ DataX/SeaTunnel: 生成配置 → 派发 worker 执行 → 回传状态
   │
   └─▶ DB_SYNC: datasource API 读源表 → 按字段映射写目标表 → 更新实例
   │
   ▼
更新 PipelineInstance（status=1 成功 / 2 失败，rows / error / duration）
```

---


### 受管物化与 OneSQL 预览

以订单链路的 `pipe_pr2b_ads` 为首个部署样例。平台库升级时执行 `db/migrate-materialization-publication.sql`，到 ADS **目标 MySQL 库**执行 `db/mysql-materialization-target.sql`。在 DB-Sync 任务配置中合并 `{"serveMaterialization":true}`，写入方式设为 `REPLACE_BY_BIZ_DATE`，业务日列使用 `stat_date`。用已配置质量门禁的工作流重跑目标业务日。

受管物化适用于普通标识符命名的 MySQL InnoDB 目标。数据和版本标记共享目标事务，目标账号需可读写版本表。每张受管目标表须由已启用受管物化的管道独占写入，关闭同表的手工写入和其他写入链路。无受管标记的历史版本重跑后才可预览。质量门禁未通过时跳过写入，保留前次成功版本。数据和发布证据暂时不一致时，预览拒绝展示并提示稍后重试。

在 **数据地图 → 资产详情 → 发布记录 → 在 OneSQL 预览物化** 查看目标分区。也可调用 `GET /api/query/onesql/materializations/{assetId}/preview?bizDate=YYYY-MM-DD`。响应包含分区、发布实例、质量快照、缓存命中标记和最多 100 行结果。每次请求重新检查资产授权、质量证据、行列策略和目标版本。配置了行列策略的表暂不提供该预览。

查询服务对每账号、资产、业务日和发布版本短暂复用结果。每个服务实例最多 128 条，单条不超过 64 KiB，有效期 30 秒。命中时仍向目标库核对版本；新发布自然使用新缓存键。「刷新结果」绕过命中，重新读取数据。通用 SQL 和 BI 原有查询入口继续使用各自的查询链路。MySQL 受管 ADS 可供现有 BI 数据集直接消费。


## 4. 数据模型设计

### pipeline_task（管道任务定义）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | 任务 ID |
| task_name | varchar(128) | 任务名称 |
| task_type | varchar(32) | 任务类型：SYNC（同步）/ ETL（加工） |
| engine | varchar(32) | 引擎：DB_SYNC / DATAX / SEATUNNEL |
| source_ds_id | varchar(32) | 源数据源 ID |
| source_table | varchar(128) | 源表 |
| target_ds_id | varchar(32) | 目标数据源 ID |
| target_table | varchar(128) | 目标表 |
| source_query | text | 源查询（可选，自定义 SQL） |
| field_mapping | text | 字段映射（JSON） |
| config | text | 引擎扩展配置（JSON） |
| cron_expr | varchar(64) | 定时表达式（空=仅手动） |
| status | tinyint | 1=启用 0=停用 |
| create_time / update_time | datetime | 时间戳 |

### pipeline_instance（执行实例）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | 实例 ID |
| task_id | varchar(32) | 关联任务 |
| engine | varchar(32) | 实际执行引擎 |
| trigger_type | varchar(16) | MANUAL / CRON |
| status | tinyint | 0=运行中 1=成功 2=失败 |
| start_time / end_time | datetime | 起止时间 |
| duration_ms | bigint | 耗时（毫秒） |
| rows | bigint | 影响行数 |
| error_message | text | 失败原因 |
| worker | varchar(64) | 执行 worker 标识 |

### pipeline_worker（worker 注册表，可扩展）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | worker ID |
| worker_name | varchar(128) | worker 名称/地址 |
| engines | varchar(128) | 支持的引擎，逗号分隔 |
| status | tinyint | 1=在线 0=离线 |
| last_heartbeat | datetime | 最近心跳 |
| create_time | datetime | 注册时间 |

---

## 5. API 设计

前缀 `/api/pipeline`，经网关转发到 `data-khaos-pipeline`。

### PipelineTaskController

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/pipeline/task/page` | 分页查询任务 |
| GET | `/api/pipeline/task/{id}` | 任务详情 |
| POST | `/api/pipeline/task` | 新增任务 |
| PUT | `/api/pipeline/task` | 修改任务 |
| DELETE | `/api/pipeline/task/{id}` | 删除任务 |
| POST | `/api/pipeline/task/{id}/run` | 手动触发运行 |
| GET | `/api/pipeline/task/engines` | 引擎列表（可扩展） |

### PipelineInstanceController

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/pipeline/instance/page` | 分页查询实例 |
| GET | `/api/pipeline/instance/{id}` | 实例详情 |
| GET | `/api/pipeline/instance/task/{taskId}` | 某任务的历史实例 |

### 交互示例（触发运行）

```bash
POST /api/pipeline/task/{id}/run
# 响应：{ code:0, data:{ instanceId:"..." } }
```

---

## 6. 权限模型

复用平台「组织 → 项目组 → 人」三级权限 + 能力位。新增能力位位定义于
`com.datakhaos.permission.api.service.PermissionConstants`：

| 能力位 | 说明 |
|---|---|
| `pipeline:manage` | 管道任务管理（增删改） |
| `pipeline:browse` | 管道任务 / 实例查看 |
| `pipeline:run` | 触发管道执行 |

超级管理员天然具备全部能力位。pipeline 服务通过 `MetadataHolder` + 能力位校验实现鉴权（参考 dquality 的 `DqAuthContext` 模式）。

---

## 7. 部署与运维

- **pipeline 服务**：作为独立微服务容器 `dk-pipeline`，主机端口 `8092`，复用通用 Dockerfile。
- **执行引擎**：DataX / SeaTunnel 以子进程方式在 pipeline 容器内执行（需在容器内安装引擎并配置 `DATAX_HOME` / `SEATUNNEL_HOME`）；DB-Sync 走 JDBC 直连，无需引擎。后续可演进为独立 worker 容器 `dk-pipeline-worker`。
- **数据库**：`pipeline_task` / `pipeline_instance` / `pipeline_worker` 三张表，随 `db/mysql-init.sql` 初始化。
- **网关**：`/api/pipeline/**` 路由到 pipeline 服务。

---

## 8. 路线图

- [x] 架构规划与引擎 SPI 设计
- [x] 数据模型与 API 契约
- [x] pipeline 服务最小闭环（DB-Sync 兜底引擎真实可跑，已端到端验证）
- [x] DataX / SeaTunnel 引擎适配器（生成 job.json / conf 并调 CLI 子进程执行）
- [x] 前端「数据同步任务」页面（任务列表/创建/运行/实例）
- [ ] 独立 worker 容器（Python + 引擎 CLI）接入
- [ ] 定时触发（对接 schedule 服务）
- [ ] 任务 DAG 编排 / 依赖
- [ ] 引擎监控看板（写入 ops 模块）