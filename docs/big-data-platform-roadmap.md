# Data Khaos 大数据平台路线图

Data Khaos 的首个核心产品是离线数据开发与运维平台。PaaS 管理从数据同步、数仓加工、编排与质量到查询服务的生命周期。BI 独立消费数据服务。Hadoop、Spark、Trino 等由独立引擎提供执行能力。国家数据技术文件保留为[跨域互通与 AI 数据集扩展参考](standards/README.md)。

## PaaS 能力清单与交付顺序

P0 指平台基础能力，P1 指规模化使用所需能力。里程碑按可独立验收的业务场景推进。离线订单链路继续作为首发验收，CDC 与流计算使用各自的输入、位点、回放和故障恢复验收。

| 能力域 | P0 与当前进度 | P1 与当前进度 |
| --- | --- | --- |
| 数据集成 | 多源接入已有 JDBC 连接器。CDC 尚缺可验收链路，先做 MySQL 单源的全量快照、增量位点与断点恢复。 | 批流同步现在有批处理适配。流式连接器及统一运行契约待补。 |
| 存储计算 | 湖仓／数仓已有 Hive 和 HDFS 参考环境，待验收分区写入与重算。批处理已有 SQL 和管道，流计算执行及恢复待接入。 | 交互查询已有 OneSQL 和查询任务中心，Trino 等多引擎对接待做。 |
| 数据开发 | SQL IDE 和调度编排已有入口，业务日期、补数批次互斥、质量门禁已在 MySQL 离线示例实测。 | 已支持工作流版本快照及恢复为停用草稿。后续补发布审批、运行绑定发布版本和环境晋级。 |
| 数据治理 | 元数据、表级血缘和权限模块已具备基础。PR 3 已打通 MySQL 分区发布证据和数据地图入口，权限继续验证项目隔离及查询链路的一致性。 | 数据质量已有规则、任务及快照。阻断性规则随离线链路提前验收。 |
| 数据服务 | 用 ADS 产出作为离线演示交付点。 | mart 已有指标和语义层。资产目录已有数据地图，市集发布流程待补。独立数据服务 API 的发布、鉴权和配额待做。 |
| 运维运营 | 平台服务监控与告警首版从注册发现和服务连通状态入手。已有项目组权限及项目运行巡检、失败筛选和补数入口。多租户的资源、执行和存储隔离仍需端到端验证。 | SQL 开发编辑器已有个人后台查询用量和按日任务下钻。资源消耗、项目归因、预算与配额仍待补。 |

**交付顺序**。先把离线链路和平台服务告警做成可复现验收。并行梳理租户隔离边界。随后落地单源 CDC 与流式执行参考链路，再推进发布、数据服务和成本治理。引擎负责数据处理，PaaS 负责接入配置、任务生命周期、证据和治理。BI 消费指标和数据服务。

## 当前分层

| 层 | 仓库现状 | 推荐方向 |
| --- | --- | --- |
| 采集 | datasource 提供 JDBC 连接，pipeline 提供 DB_SYNC、DataX 和 SeaTunnel 适配。DataX 已有 MySQL 与 Hive 的方向适配，运行依赖外部 DataX。 | 首发选 MySQL 到 Hive 的批同步。日志采集与数据库 CDC 按需求分别扩展，Flume 与 Canal 对应不同场景。 |
| 存储与计算 | Compose 提供单节点 HDFS 和 Hive 4.0.0。Hive 使用 Derby 元存储、本地 Tez。未发现 YARN 或通用 Spark 作业提交的参考部署。 | 演示先沿用 HDFS/Hive。接入影响范围扩大后，再独立部署 Hive Metastore，让 Spark SQL 与 Trino 共享目录。Spark 批作业可运行在 YARN 配置中。 |
| 编排与运维 | schedule 提供 Cron、依赖和日志，workflow 提供 DAG 和节点重试，pipeline 有执行实例。SYNC 与 WORKFLOW 等待实际执行结果，未接入的 REFRESH 和 PUSH 明确返回失败。 | schedule 负责时点触发，workflow 负责 DAG 与状态，pipeline 负责搬运。先打通真实运行和状态回传。 |
| 治理 | metadata 提供目录及表级血缘，dquality 有规则、任务、快照。auth、permission、approval 提供平台身份、授权和审批。 | 从任务产出自动记录表级血缘，以质量结果控制下游。项目权限留在平台，Ranger 随引擎对接并验收数据访问策略。 |
| 查询 | query/OneSQL 管理 SQL 工作台及查询；gateway 负责应用 API 路由；mart 提供指标和语义查询。未发现 Trino/Presto 接入实现。 | 先选 Trino 作为交互查询引擎。平台继续负责用户身份、查询生命周期和审计。 |
| 结果服务 | 当前 MySQL 用于平台元数据，已有 MySQL 和达梦数据源连接器。 | 仓内 ADS 保留分析结果。需要业务侧消费时才发布到独立的 MySQL 或达梦服务库，明确同步周期、幂等键、校验和回退。 |
| BI | visual、mart 和 Vue 前端已有分析与仪表板能力。 | 先消费一套统一指标和质量状态。按[架构分层](architecture.md#paas-与-bi-演进)拆分 visual 与 BI Web，mart 保留在 PaaS。 |

部署基于 [Compose](../docker/docker-compose.yml) 和 [Hive 配置](../docker/hdfs-conf/hive-site.xml)，任务行为见 [JobExecutor](../data-khaos-schedule/src/main/java/com/datakhaos/schedule/service/JobExecutor.java) 与 [WorkflowRunner](../data-khaos-workflow/src/main/java/com/datakhaos/workflow/runner/WorkflowRunner.java)。以上是代码与配置盘点。

Hive 4.0 已移除 Spark 执行引擎。计划采用独立 Spark SQL 读取或写入由 Hive Metastore 管理的表。Trino 对接同一目录和存储。单独验收表格式、Hive Metastore 与各引擎版本、访问身份和网络连通性。 [Apache Hive 变更](https://issues.apache.org/jira/browse/HIVE-27934) · [Spark Hive Tables](https://spark.apache.org/docs/latest/sql-data-sources-hive-tables.html) · [Trino Hive connector](https://trino.io/docs/current/connector/hive.html)

## 里程碑

### M1 · 第一条可靠的离线数仓

以订单为演示场景，DataX 从 MySQL 同步 ODS，SQL 写入 DWD 订单明细、DWS 地区日汇总和 ADS 地区成交额。完成任务定义、业务日期、运行实例、依赖、日志、失败重试与按日期补数。优先解决 SYNC 的虚假成功路径，兼容保留已有手工触发入口。用示例数据和端到端测试分别验证四层产出、质量失败阻断、失败重跑不重复、两天补数互不覆盖以及看板值一致。

### M2 · 让治理进入生产过程

用稳定的 asset ID、run ID、业务日期与分区建立任务、表、质量报告和指标之间的关联。先做运行驱动的表级血缘，再补列级解析。支持从异常任务定位受影响的表与指标。将交互查询、BI 预览和导出收敛到同一访问控制策略，核对策略不可用时的行为。单独交付 PaaS 与 BI 镜像以及迁移脚本。

### M3 · 扩展参考部署和生态

选一套共享 Hive Metastore 的 Spark SQL 和 Trino 组合。固定引擎版本，发布兼容矩阵。Spark 验收提交、状态、取消与运行日志，Trino 验收有界查询、取消、身份透传与审计。Ranger 首先覆盖一个引擎和两个用户，实测拒绝和审计链路。CDC、日志收集和实时计算基于具体用户需求接入。面向贡献者发布连接器契约、样例连接器与测试数据，支持社区按底层引擎贡献。

实时计算、CDC 和数仓的阶段输入、输出和验收见[实时数仓实施计划](paas/realtime-warehouse.md)。

## 大厂对标

- 阿里云 DataWorks 对标任务开发、提交发布、调度和质量的完整流程。首发可以用一条订单任务从开发到上线作为演示。 [阿里云 DataWorks 数据开发](https://www.alibabacloud.com/help/en/dataworks/user-guide/data-development-process-guidance/)
- 美团公开实践可以对标故障影响分析。目标是由失效任务定位下游指标和看板，优先实现可复现的影响分析。 [美团技术团队](https://tech.meituan.com/2022/06/16/Recommendation-System-Quality-Model.html)
- 火山引擎 DataLeap 对标多引擎之上的研发、治理、资产和指标体验。先完成一组引擎，再以可测试的适配协议扩展。 [火山引擎 DataLeap](https://docs.volcengine.com/docs/big-data-research-and-development-governance-suite/introduction-to-dataleap?lang=zh)

个人技术产出以仓库内可复现演示、设计决策、故障和恢复测试、运行数据及基准记录为主。性能数据包含运行环境、数据量、配置和结果。首个公开发行以可复现的批链路为验收，发行说明按实际验证范围列出能力。
