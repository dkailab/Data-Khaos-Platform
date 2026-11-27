# 实时计算模块完整性与前沿能力对照

> 走查日期：2026-11-27。初版走查后同日完成 P0 闭环；方法：全仓只读走查 + 真实接口验收，
> 对照 `docs/paas/realtime-warehouse.md` / `docs/paas/flink-studio.md` / `docs/reuse-realtime-platform.md`，
> 并以 Apache Flink 官方文档与当前业界公开实践校准「前沿」清单。代码事实以本仓库当前 HEAD 为准。

## 1. 结论先行

**既定范围的 P0 已闭环，剩下的是 P1 生产接入加固。**

- 仓库里**没有**名为 realtime / flink / streaming 的独立 Maven 模块，实时能力分布在 `data-khaos-workflow`（控制面）、`data-khaos-query`（查询侧契约）、`data-khaos-schedule`（定时提交）、`data-khaos-web`（页面）和 `examples/realtime-orders`（真实样例）五处。
- 「作业开发 + 提交 + 观测告警 + 保存点恢复 + 订单实时数仓 + 批流对账 + 版本化实时 ADS API」这条主线**已经打通并且经过真实引擎验证**，不是空壳。
- 本轮补齐三处平台闭环：实时作业可按**已审批版本**由调度器提交；数仓 API 发布可绑定**审批版本**（改稿即失效）；实时查询由**版本配置驱动列映射与推进信号表**，不再写死地区成交额口径；管道计数进入质量域。
- 仍缺的是**生产接入能力**：CDC 多表路由与 schema 兼容、实时血缘自动采集、资源配额与容量趋势。这些是 P1，不再是「功能没实现」。
- 业界前沿里真正与我们相关的是**声明式物化表（FRESHNESS 驱动流批一体）**、**Schema Evolution**、**流式湖仓表格式**和**实时质量/血缘内嵌**。其中前两项值得对齐设计，后两项属于存储栈与基础设施层，当前阶段不建议引入。

## 2. 现状能力矩阵

| 层 | 已实现 | 证据 |
| --- | --- | --- |
| 引擎接入 | Flink 1.20 SQL Gateway + JobManager REST 真实交互：会话、语句、JAR 运行、取消、savepoint 触发、checkpoint、watermark、反压、Kafka lag、指标聚合 | `data-khaos-workflow/.../flink/FlinkRuntimeClient.java` |
| 作业控制面 | 作业/函数/JAR 版本、并发编辑锁、SQL 校验与 EXPLAIN、调试 SELECT（≤100 行）、提交幂等键、活动运行互斥、状态对账、取消、保存停机、从 savepoint 恢复 | `FlinkDeploymentService.java`、`FlinkStudioService.java`、`FlinkRunSql.java`、`FlinkDeploymentController.java` |
| 观测告警 | 检查点连续失败、持续反压 ≥50%、Kafka 积压、水位线停滞四类后台巡检 + 站内通知；作业级检查点/指标/事件时间诊断弹窗 | `workflow/runner/Flink*Monitor.java`、`*AlertScheduler.java`、`InternalFlinkNotificationController.java` |
| 实时数仓样例 | MySQL CDC → Kafka ODS → DWD → DWS → ADS，窗口函数、故障重启恢复、旧事件回放幂等、批流逐键对账；真起 Flink 1.20.3 + CDC 3.6.0 容器 | `examples/realtime-orders/{verify.py,warehouse.sql,batch-reconcile.sql,orders-cdc.yaml}` |
| 查询侧契约 | `data_mode=BATCH/REALTIME`、四态结果、双时钟、静默窗口、时效分档、批流对账、应用凭据/配额/审计 | `RealtimeAdsQueryService.java`、`WarehouseApiService.java` |
| 前端 | 全屏 SQL 作业工作台（多标签、高亮、版本 diff/restore）、实时运维页、检查点/指标/告警/诊断弹窗 | `views/workflow/flink/*`、`views/realtime/RealtimeOperations.vue` |

**边界条件**：`FLINK_RUN_PROJECT_ID` 只能绑定单个项目，`FLINK_RUN_SQL_GATEWAY` 为空则提交入口默认关闭；样例的多目标 `BEGIN STATEMENT SET` 脚本无法由平台提交（工作台首版只收单条 `INSERT`）。这两条在 `docs/paas/flink-studio.md` 已自述。

## 3. 与既定实施计划的对照

`docs/paas/realtime-warehouse.md` 的 RT1–RT4 中：

- **RT1 单表 CDC / RT2 实时数仓 / RT3 作业控制面**：主线完成，样例覆盖快照、增量、窗口、恢复、回放、对账。
- **RT4 查询及治理**：查询侧完成（时效证据 + 批流对账），**目录血缘、指标口径登记、按业务日补算与结果发布尚未完成**。
- 计划里标注为「下一步」的 CDC 多表路由与快照并发、schema 兼容策略、隔离调试晋级与审批、资源配额与自动扩缩容，**均未在代码中出现**。

所以「是否完整」要分两句话回答：**作为 RT1–RT3 的实现是完整的；作为 RT4 和生产能力清单（P0/P1）是未完成的**。

## 4. 业界前沿对照与取舍

| 前沿能力 | 业界现状 | 我们 | 建议 |
| --- | --- | --- | --- |
| **声明式物化表** | Flink SQL Materialized Table：声明 `FRESHNESS` + 查询定义，引擎自动推导 schema 并生成刷新管道；`FRESHNESS` 决定 CONTINUOUS（转 checkpoint 间隔）或 FULL（转调度周期）。官方文档仍标注能力演进中、部署形态有限制 | 手工维护 `BATCH`/`REALTIME` 两套契约与两套 SQL（`warehouse.sql` / `batch-reconcile.sql`） | **对齐概念，不照搬实现**：把 `freshness_fresh_seconds` 升级为「新鲜度目标 → 刷新模式」的单一声明，减少双份口径维护 |
| **Schema Evolution** | Flink CDC 已有 schema evolution 机制；上游加列/类型变更的兼容与阻断策略是生产接入的硬要求 | 样例固定表结构；`pipeline` 有字段映射但无变更检测与兼容策略 | **P1 必补**：多表路由 + 兼容列放行 / 破坏性变更阻断 + 回放后逐键对账 |
| **流式湖仓表格式**（Paimon / Iceberg / Fluss） | Paimon 原生 changelog、ACID 与 Flink checkpoint 闭环；Iceberg 强调多引擎互操作与 row lineage；湖流一体用于秒级新鲜度与 AI 特征 | ADS 写 MySQL，批流对账靠手工 SQL | **暂不做**：这是存储栈替换，解决不了当前的调度/审批/通用查询缺口，且会偏离国产化数据库路线。用吞吐与可恢复性数据触发决策 |
| **Flink 2.0 存算分离 / 自动扩缩容** | ForSt 状态后端把状态放远端存储，计算与状态独立伸缩；弹性扩缩容成为引擎层能力 | 绑定外部 Flink 集群，未在平台暴露状态后端与资源策略 | **不造轮子**：通过集群选型与部署配置享受；平台只补资源配额用量与容量趋势 |
| **内嵌实时数据质量 + 实时血缘** | 实时链路的质量规则应嵌在作业处理逻辑里，脏数据传播极快；血缘需能追到作业与运行 | `dquality` 只有 5 类批规则；`FlinkMetricsView` 只用于告警；`MetaTableLineage` 是静态表级登记 | **P0 必补**：让告警采样点进入质量域，并从 SQL 自动解析产出血缘 |
| **AI 特征实时化** | 流式计算结果直接供在线推理 | 无 | **暂不做**：业务场景尚未提出，先把订单链路做扎实 |

## 5. 建议优先级（按性价比排序）

### P0：把现有能力连成闭环

1. **三层对账与质量联动** — **已完成（2026-11-27，含当日加固）**
   - `dquality` 增加 `PIPELINE_COUNT` 规则：`ruleConfig.metricsSql` 返回
     `stage / records_in / records_out`（可选 `stage_order`），`QualityEngine` 对相邻阶段做计数对账。
   - 判定口径：`偏差 = 上游输出 - 下游输入`，正偏差记丢失、负偏差记重复，两者都计入
     `Σ|偏差| / 源端输入` 的计数偏差率；源端为 0 但下游有数据判采样不一致并报 ERROR，
     不再按 0 偏差默认通过。有 `stage_order` 时按其排序，不依赖 SQL 的 ORDER BY。
   - `workflow` 新增 `PipelineCountSampleScheduler`（默认关闭，需 `FLINK_PIPELINE_COUNT_SAMPLER_ENABLED=true`）
     周期采集运行中算子的累计 `numRecordsIn/Out` 写入 `dk_pipeline_stage_count`：
     同一轮共用 `sample_batch`，并记录拓扑序 `stage_order`；按 `FLINK_PIPELINE_COUNT_RETENTION_DAYS`
     （默认 7 天）清理历史采样。指标缺失的算子不写入，避免把缺失当 0。
   - `RealtimeReconcile` 增加容差模式：版本配置 `reconcile_tolerance`（0~1，null/0 为 STRICT），
     金额与订单数相对偏差均在阈值内判 `WITHIN_TOLERANCE`，回显 `mode/tolerance/matchRate` 与逐地区偏差率。
     任一侧截断或未定稿时两种模式都不判一致。
   - 验收（真实库 + 真实接口）：source=100 / dwd=95 / ads=95 → `FAIL`，`actual=0.0500`，消息定位
     `source→dwd 丢 5`；补一批完整采样后 → `PASS`，`actual=0.0000`。

2. **任务版本 × 审批版本绑定 + 定时触发** — **已完成（2026-11-27）**
   - 新增共享契约模块 `data-khaos-approval-api`：`ApprovalEvidenceDto` + `ApprovalApiClient`，
     查不到返回空，审批服务不可用抛异常（调用方按「无法确认」拒绝，不静默放行）。
   - `app_apply` 增加 `target_revision`（历史数据为 NULL，不参与版本匹配），`target_id` 扩到 64；
     新增 `GET /api/approval/apply/approved?applyType=&targetId=&targetRevision=`。
   - 发布门禁：`WarehouseApiService.publish()` 在 `DATA_KHAOS_WAREHOUSE_APPROVAL_REQUIRED=true` 时
     要求存在绑定当前 `revision` 的已通过审批。编辑会推进 revision，旧审批自然失效。
     默认关闭，避免打断存量发布脚本。
   - 调度新增 `FLINK` 任务类型：`JobExecutor` 先查 `FLINK_JOB` 审批，再按作业版本提交；
     `WorkflowApiClient` 增加 `triggerFlink` / `flinkRun`，以计划触发时刻生成稳定 requestId，
     同一版本重复触发命中同一条运行记录；以 `SUBMITTED` 视为成功，`RECONCILING` 等状态按
     「已受理未确认」停止重试。
   - 内部调用没有用户 JWT：`WorkflowProjectAccess` 对携带 `SUPER_ADMIN` 角色的无 Authorization
     请求放行，网关进来的用户请求仍走完整项目权限校验。
   - 验收（真实接口）：无审批发布 → `code=403`；审批 rev1 后发布成功；改稿到 rev2 后旧审批被拒；
     重新审批 rev2 后发布成功。调度侧：未审批 → 作业日志「该作业版本未取得审批」；
     审批后 → 通过门禁并进入实时作业提交。

3. **实时查询通用化** — **已完成（2026-11-27）**
   - `warehouse_api_version` 增加列映射：`realtime_key_column / value_column / count_column /
     observed_column / date_column / value_divisor / progress_table / progress_date_column /
     progress_advanced_column / progress_count_column / progress_event_time_column`。
     全部为 NULL 时按默认地区成交额口径解释（region / gmv_cents / paid_order_count /
     observed_at / biz_date，金额除以 100，推进表 `<结果表>_events`），存量版本行为不变。
   - `RealtimeAdsQueryService` 的查询与推进 SQL 全部由配置生成；列名/表名必须是纯标识符，
     配置层与读取层各校验一次。响应契约（region/gmv/orderCount + 时效证据）保持不变。
   - 验收（真实接口）：新建 `ads_city_sales`（city / amount_yuan / order_cnt / seen_at / stat_date）
     与独立推进表，只改版本配置、不改 Java，即返回 `880.50 / 1200.00`、`PRODUCED`、`LAGGING`，
     推进信号 `eventCount=32` 正确回显。

### 本轮顺带修复的已知缺陷

- `query_job` 只在 `db/init.sql`（达梦）存在，`db/mysql-init.sql` 缺失，导致 MySQL 部署上
  `QueryJobWorker` 每分钟报 `Table 'query_job' doesn't exist`。已补 MySQL 建表、迁移脚本
  `db/migrate-query-job.sql` 与本地库。
- 管道采样原先用 `ORDER BY sampled_at DESC, vertex_id` 且没有批次概念，会把不同轮次、同一算子的多行
  混在一起对账；已改为一轮一批 + 拓扑序。
- 原判定把「下游比上游多」（重复写入）当成 0 丢失通过；已计入计数偏差率。

### P1：生产接入能力

4. CDC 多表路由 + schema 兼容策略（计划 RT1 的生产化项）。
5. 实时作业血缘自动采集：从 SQL 解析出 CDC → ODS → DWD → DWS → ADS，关联运行 ID 与数据服务版本。
6. 资源配额、状态存储用量与容量趋势；自动扩缩容等引擎能力延后评估。

### 不建议现在做

Paimon/Iceberg 湖仓迁移、Flink 2.0 运行时的深度定制、AI 特征平台。理由：当前瓶颈在平台闭环与可信性，不在存储格式或引擎版本；引入这些会显著扩大改动面而不解决已识别的风险。

## 6. 验收口径

以下五项均已在本机真实环境（MySQL + 网关 8099 + 重建镜像）跑通：

- **丢数场景**：source=100 / dwd=95 / ads=95，`PIPELINE_COUNT` 返回 `FAIL`，
  `actual=0.0500`，消息含 `source→dwd 丢 5`；补一批完整采样后 `PASS`。
- **重复场景**：下游输入大于上游输出时计入 `duplicate`，偏差率升高，不再按 0 丢失通过。
- **双时钟场景**：观测时间旧、处理时间新，断言 `freshness=STALE` 但 `processingLagSeconds` 正常
  （由 `RealtimeAdsQueryTest` 覆盖）。
- **审批场景**：草稿 → 发布被拒（403）→ 审批 rev1 → 发布成功 → 改稿到 rev2 → 发布被拒 →
  重新审批 rev2 → 发布成功。
- **调度场景**：未审批的 `FLINK` 任务被拒并写明原因；审批后通过门禁进入作业提交；
  同一计划时刻的 requestId 稳定，重复触发命中同一条运行记录。
- **通用查询场景**：`ads_city_sales`（city / amount_yuan / order_cnt / seen_at / stat_date）
  配独立推进表后，不改 Java 即返回 `880.50 / 1200.00`、`state=PRODUCED`、`freshness=LAGGING`。

## 7. 一句话总结

实时计算的**开发工具链、数据链路与平台闭环都已打通**：作业能开发、能观测、能按审批版本由调度提交，
结果能按配置化契约对外服务，并用「批流对账 + 管道计数 + 质量快照」三处证据互相校验。
剩余工作集中在生产接入侧（CDC 多表路由与 schema 兼容、实时血缘自动采集、资源配额与容量趋势），
属于 P1，不再是「功能没实现」，而是「规模化接入前的加固」。
