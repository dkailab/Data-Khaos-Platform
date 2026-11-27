# 实时血缘与调度执行层改造

> 日期：2026-11-27。目标：把实时数据链路和调度补齐到「中小公司可交付」的程度，
> 只依赖 MySQL，不新增组件，单副本默认可用，多副本自动安全。

## 1. 实时血缘（Flink SQL 表级）

### 1.1 设计取舍

不引入 Calcite / jsqlparser 做完整 SQL 解析，原因是：

1. 表级血缘只需要「目标表 + 源表」，不需要列级映射和完整 AST。
2. 项目已有 `MetadataService.analyzeSqlLineage` 走轻量正则的批 SQL 血缘，保持一致风格。
3. 引入 jsqlparser 会和 MyBatis-Plus 分页插件的版本冲突（历史已知问题）。

所以 `FlinkSqlLineage` 用正则 + 词法状态机做**保守解析**：
解析不出来就返回空，绝不猜测；能解析的就给全。

### 1.2 解析能力

- `CREATE TABLE ... WITH (...)` 提取 connector、物理表名（`database.table` 或 `topic`）、
  显式 `datakhaos.asset-id` 提示。
- `CREATE VIEW AS ...` 与 CTE（`WITH x AS (...)`）展开到物理表。
- `INSERT INTO ... SELECT` 识别目标表；`FROM a, b` 与 `JOIN c` 识别源表。
- 忽略字符串字面量与函数内的 `FROM`（如 `EXTRACT(YEAR FROM ts)`）。

### 1.3 资产映射优先级

1. DDL 显式 `datakhaos.asset-id`（唯一可信，Kafka/Pulsar 只认这个）。
2. 物理表名 → 元数据资产目录 `meta_table` 匹配（jdbc / mysql-cdc 等）。
3. 逻辑名 → 资产目录匹配。
4. 都解析不到 → `flink_table_lineage` 保留名称、`source_asset_id` 为空，页面可展示「没对上资产」。

### 1.4 落库

- `flink_table_lineage`：按「作业 + 版本」保存，版本不可变所以整体替换。
- 两端都有资产时，回写 `meta_table_lineage`（`relation_type = FLINK`，幂等去重）。
- 作业提交时跟随 `FlinkDeploymentService.submit` 记录一次；解析失败不影响提交。

### 1.5 接口与入口

- `GET /api/workflow/flink/projects/{p}/assets/{id}/versions/{v}/lineage`（首次自动解析）
- `POST .../lineage/refresh`（手动重新解析）
- 前端 `JobSqlWorkspace` 工具栏「血缘」按钮 + 弹窗。

## 2. 调度执行层（参考 DolphinScheduler 领取模型）

结论：**不引入 DolphinScheduler 作为调度底座**，只借鉴它的执行层机制，
用 MySQL 原子 UPDATE 实现，保持单副本简单、多副本安全。

### 2.1 三个机制

| 机制 | 实现 | 对应 DS 概念 |
|------|------|-------------|
| 原子领取 | `UPDATE schedule_job SET owner=? WHERE next_fire_time<=now AND (owner IS NULL OR lease_until<now)` 返回 1 才执行 | `t_ds_command` claim |
| 租约 | `lease_until` + 实例心跳续租，进程崩溃后其他实例接管 | worker 心跳 |
| 执行器组 | `executor_group`，空=任意实例，非空只由同组实例领取 | worker group |

### 2.2 字段与索引

`schedule_job` 新增 `executor_group / priority / next_fire_time / owner / lease_until`；
索引 `(status, next_fire_time)` 与 `(executor_group, status, next_fire_time)`。

调度器不再每 5 秒全表拉任务现算 cron，改为按 `next_fire_time` 扫描到期行。

### 2.3 任务 SPI

新增 `JobHandler` 接口（`type()` + `execute(JobContext)`），把 `JobExecutor` 里的
`switch` 拆成 5 个 handler：SQL / QUALITY / WORKFLOW / SYNC / FLINK。
新增任务类型不再改执行器，只需注册一个 Bean。

`JobContext` 收敛了「业务日期、幂等键、超时、参数解析」这些跨类型的规则，
保证业务日期与幂等键只由「计划触发时刻」推导，重试/换实例不漂移。

### 2.4 单副本 vs 多副本

- **单副本**（中小团队默认）：`owner` 只有一个实例抢占，行为与之前一致，只是多了 `next_fire_time` 索引，扫描更稳。
- **多副本**：靠 `owner + lease_until` 互斥，同一任务不重叠执行；错过的那次跳过（misfire=skip）。

## 3. 验收

- `FlinkSqlLineageTest`：8 用例覆盖 INSERT/视图/CTE/逗号连接/子查询/函数内 FROM/asset 提示/去重/无 INSERT。
- `JobContextTest`：业务日期与幂等键由计划触发时刻推导。
- 全量 `mvn -o test` 通过；`vue-tsc` + `vite build` 通过。

## 4. 迁移脚本

- `db/migrate-flink-lineage.sql`：`flink_table_lineage` 表。
- `db/migrate-schedule-claim.sql`：调度字段与索引。
- `db/mysql-init.sql` / `db/init.sql`：同步补齐 `schedule_job` 新字段（新装库）。
