# 从旧「实时计算平台」资料中可复用的设计

> 资料来源：`/Users/dk/Documents/data-khaos/实时计算/`（396 MB / 888 文件，2020–2021 年）。
> 分析方法：读源码 DDL、需求/概要设计正文、运维脚本与架构 PPT，并与 Data Khaos 当前代码逐项对照。
> 结论先行：**能复用的是机制，不是代码**。教学代码与运维脚本均已过时；真正值钱的是四块经过生产检验的设计。

---

## 1. 资料盘点：什么有用，什么没用

| 目录 | 价值 | 说明 |
| --- | --- | --- |
| `数据同步/数据同步一期-上线sql/` | **高** | 14 张生产 DDL，完整呈现「作业定义 → 审批 → 发布 → 执行实例 → 监控 → 审计」的数据模型 |
| `实时计算平台1.2概要设计.docx` | **高** | 配置分离、偏移量恢复、资源管理的原始设计说明 |
| `实时计算平台1.3.2需求文档.docx` | **高** | 资源版本对比/回滚、审计范围的具体交互规则 |
| `数据同步/数据同步方案1.2.0mysql2kudu需求.docx` | **高** | 第 51–60 行「数据质量保障」是全 folder 最有价值的一段 |
| `资料/他山汇-pipeline.pptx` | **高** | slide 8 分区完整性判定、slide 10 大表治理取舍 |
| `文档&手册/2021年初的glink架构梳理.pptx` | 中 | slide 12 监控数据获取链路，slide 15 作者自陈的缺陷 |
| `文档&手册/Flink监控.docx` | 中 | Metrics 体系与 PushGateway 取舍；我们已有对应实现 |
| `脚本/*.py` | **仅参考检查项** | Python 2 语法、硬编码生产库账号、shell 拼命令，**不可复制代码** |
| `分享/4.代码/FlinkTutorial`、`UserBehaviorAnalysis` | 低 | 尚硅谷教学代码，Flink 1.10 时代 API（`TimeCharacteristic`、`AscendingTimestampExtractor`）已废弃 |
| `分享/1.课件文档/*.pdf`、`新人串讲/*.pptx` | 低 | 教学与团队介绍，知识本身已从代码与文档中获得 |
| `数据同步/数据库密码.txt`、`dpl数据库密码.docx` | **不要看** | 明文凭据，按安全规范不读取、不入库、不复述 |

---

## 2. P0：建议直接补进 Data Khaos 的三项

### 2.1 三层质量对账闭环

**出处**：`数据同步方案1.2.0mysql2kudu需求(1).docx` 第 51–60 行；`数据同步1.0技术方案.docx` 第 103–108 行。

旧平台的原则是「**实时链路不保证 100% 正确，靠多层对账保证最终一致**」：

| 层 | 做法 | 旧平台参数 |
| --- | --- | --- |
| ① 管道内计数对账 | source 接收量 → 过滤量 → func 处理量 → sink 写入量，各阶段必须能对上 | 用于定位链路内哪一段丢数 |
| ② 定时批流对账 | 每 10 分钟比一次数据量 + 数据内容 | 阈值 **95%**；内容只抽能和批表对上的字段 |
| ③ 双时钟延迟 | **不用 Kafka lag 判断延迟**，用 `event_time(es)` 与 `current_time` 的差、`process_time(ts)` 与 `current_time` 的差 | 两个差值分别反映「数据新不新」和「链路堵没堵」 |

配套两条约束：乱序监控不做，但**下游必须幂等**以支持回溯；补偿方式是回放源库全量数据。

**Data Khaos 现状**：
- `dquality` 已有 5 类规则（`NOT_NULL` / `UNIQUE` / `VALUE_RANGE` / `CUSTOM_SQL` / `CUSTOM_PROBE`），但没有「管道阶段计数」这种规则类型；
- `workflow` 的 `FlinkMetricsView` 已经在采 in/out/watermark/backPressure，但只用于告警，没落到质量域做对账；
- PR D 刚做的 `RealtimeAdsQueryService` 只比较 `observed_at` 与当前时间——**这是单时钟**，对应旧平台的 event_time 一侧，缺 process_time 一侧；
- 对账接口是精确逐地区匹配，没有容差模式。

**落地建议**（约 5 人日）：
1. `DqRule` 增加 `PIPELINE_COUNT` 类型，规则配置里记录各阶段的 metric 名；`DqExecution` 从 `FlinkMetricsView` 采样点做相邻阶段差值断言，不通过就出规则结果。
2. 实时 ADS 结果表补 `loaded_at`（处理时间），契约 `RealtimeRegionGmv` 增加 `processingLagSeconds`；`freshness` 仍由 event_time 侧决定，process_time 侧单独告警——两者含义不同，别合并成一个数。
3. `RealtimeReconcile` 增加容差模式：`strict`（现状，精确匹配）与 `tolerance=0.95`（大数据量最终一致性），响应回显用了哪种模式和实际吻合率。
4. 在 PR D 交接文档写明「下游幂等是硬约束」，避免回溯时重复计数。

**验收**：造一次 source=100 / sink=95 的丢数场景，断言 `PIPELINE_COUNT` 报失败且能定位到具体阶段；把观测时间改旧、处理时间保持新，断言 `freshness=STALE` 但 `processingLag` 正常。

### 2.2 分区完整性判定（`in-progress` 静默窗口）

**出处**：`资料/他山汇-pipeline.pptx` slide 8。

旧平台把 binlog 落在 `/dt={yyyyMMdd}/hour={HH}/{topic}-{start}-{flinkTaskId}`，写入中的文件带 `_in-progress` 后缀。**只有该文件静默 `InactiveBucketThreshold = 5m` 并转成正式文件名后，才认为该小时分区完整**。

这解决的是「文件已经出现，但还在写」——比「有没有文件」强得多，正好补我们 PR D 的一个真实缺口。

**Data Khaos 现状**：`MaterializationQueryService` 靠 `dk_materialization_version` 发布证据；PR D 靠 `<table>_events` 推进信号区分 `EMPTY` 与 `NOT_YET_PRODUCED`。但推进信号是作业主动写的，如果作业写了信号、数据还在落盘，仍可能返回半成品。

**落地建议**（约 2 人日）：
1. 实时作业侧同步写「分区在写」标记（文件名后缀或推进表加 `writing` 字段）。
2. `RealtimeAdsQueryService.judge()` 增加一道判定：有推进信号但标记仍在写、或最后写入距现在不足静默窗口 → 返回 `NOT_YET_PRODUCED`（或新增 `IN_PROGRESS` 态），**不能返回 `PRODUCED` 的半成品**。
3. 静默窗口做成版本级配置，默认 5 分钟，与 `freshness` 阈值独立。

**验收**：写入未关闭时查 API，断言不是 `PRODUCED`；等待超过静默窗口后断言转为 `PRODUCED`。

### 2.3 任务版本 × 审批版本绑定

**出处**：`task_info.sql` 第 48 行 `task_version`；`approve_instance.sql` 第 31 行 `task_version`。

旧平台每次修改任务递增 `task_version`，审批实例记录它审批的是哪个版本。发布时比对两者，**审批通过后又改过任务，审批即失效**——防止「批的是 A，发的是 B」。

**Data Khaos 现状**：`warehouse_api_version` 有 `revision` 乐观锁，`approval` 模块有 `AppApprovalRecord`/`AppApprovalFlow`，但两者没有绑定。PR C/D 的发布接口只校验 revision 防并发，不校验「审批后是否被改过」。

**落地建议**（约 3 人日）：
1. `AppApprovalRecord` 增加 `target_type` / `target_id` / `target_revision`。
2. 发布接口校验 `record.target_revision == version.revision`；不等则拒绝并提示「审批后已被修改，请重新审批」。
3. 审计记录同时写下两个版本号，事后可追。

**验收**：创建草稿 → 审批通过 → 改一次草稿（revision+1）→ 发布，断言被拒绝；重新审批后发布成功。

---

## 3. P1：值得做但可排在后面的三项

### 3.1 配置分离与资源版本管理

**出处**：`实时计算平台1.2概要设计.docx` 第 18–19、28–30 行；`实时计算平台1.3.2需求文档.docx` 第 22–38 行。

设计要点：
- 作业包拆成 `app/`（任务 jar）、`conf/`（配置文件）、`lib/`（依赖 jar），本地落盘 + 定时同步 HDFS 备份。
- properties 可在线预览/编辑，注释、变量名、变量值三色标注；每次保存生成一个版本，支持**版本对比与回滚**。
- 资源分公共/项目两级，项目资源仅项目成员可见；删除资源前检查任务依赖，有依赖给出任务清单。
- 同名 jar 上传时提示「已存在，请直接引用」。

**Data Khaos 现状**：`workflow` 已有 `FlinkAsset` / `FlinkAssetVersion`，但没有 app/conf/lib 分类、配置在线编辑与依赖检查。

**建议**：作为 `FlinkStudio` 的增强，先做「资源类型 + 依赖检查 + 同名提示」（约 3 人日），版本对比回滚放第二批（约 5 人日）。我们已有资产版本表，不用重造数据模型。

### 3.2 审计类型配置化

**出处**：`audit_type.sql`——用 `controller` + `method` 两列配置哪些接口要审计，而不是在每个方法里手写日志。

**Data Khaos 现状**：`warehouse_api_call_audit` 是**调用审计**（面向机器调用方），操作审计散在各业务日志里，没有统一的配置化入口。

**建议**：加 `op_audit_type` 表 + 一个 AOP 切面，按 `controller.method` 匹配并记录操作人、参数摘要、成功与否。1.3.2 需求列出的范围可直接当种子数据：作业创建/修改、jar 版本新增、提交、删除、下载。

### 3.3 同步建表时的字段级策略

**出处**：`数据同步方案1.2.0mysql2kudu需求(1).docx` 第 25–31 行。

- 主键**可多选且顺序可拖拽**，目标表按拖拽顺序拼主键；
- 字段级「是否加密」，支持 MD5；
- 分区支持 Hash / Range，所有分区数量相乘 ≤ 80；
- 字段映射表有「是否已匹配」标志，一个目标字段只能被一个来源字段占用。

**Data Khaos 现状**：`PipelineTask.fieldMapping` 已有字段映射，`PipelinePreflight` 有分区列预检；但没有主键排序、字段加密、分区策略组合。

**建议**：先补「一目标字段只能映射一来源字段」的校验（纯校验、收益高、约 1 人日）；主键排序与加密等按需再做。注意 MD5 是不可逆的，若需要可逆要换成项目现有的加密组件。

---

## 4. 不要复用的部分

| 内容 | 原因 |
| --- | --- |
| `FlinkTutorial` / `UserBehaviorAnalysis` 全部 Java 代码 | Flink 1.10 时代 API，`TimeCharacteristic`、`AscendingTimestampExtractor`、`keyBy(int)` 均已废弃。案例思路（CEP 超时、双流 join 侧输出、布隆过滤 UV）可参考，**代码不可直接搬** |
| `脚本/*.py` | Python 2 语法（`print x`、`reload(sys)`）；硬编码生产库账号密码与内网告警 URL；用 `os.popen` 拼 shell 命令。**只借鉴检查项，不复制实现** |
| ZK / Nezha / 百川 / 天宫 / 哪吒等私有组件绑定 | 外部平台强耦合，Data Khaos 有自己的权限、审批、调度、通知体系 |
| 串行拉取所有 app 监控的实现 | 架构 PPT slide 15 作者自己指出「app 多时监控延迟」。我们 `FlinkMetricsView` 已是批量/并发取数，别倒退 |
| Prometheus PushGateway + TTL 自定义改造 | 我们已有 `FlinkMetricsView` + 四类告警调度器，能力重叠；若确需 PushGateway 只借「TTL + 只采选定 metric」的取舍思路 |

---

## 5. 与最近 PR D 的衔接

刚完成的 PR D 已经把旧平台设计里最关键的一条落地了：**不用 Kafka lag 判断数据新不新，用数据本身的可验证观测时间**。旧平台的 `<table>_events` 思路也与我们做的两级推进信号一致。

本轮建议补的 2.1 和 2.2，本质是给 PR D 加两道保险：

- **2.2 分区完整性**补的是「作业说写完了，但文件还在写」——直接避免 PR D 返回半成品 `PRODUCED`；
- **2.1 双时钟**补的是「数据新但链路堵了」——避免只盯 `freshness` 而漏掉处理侧故障。

这两项都不推翻已有契约，属于向后兼容的字段/状态扩展，改动面可控。

---

## 6. 一句话总结

这套 2020 年的资料里，技术栈和代码都过时了，但**「版本绑定审批」「in-progress 静默窗口」「三层质量对账 + 双时钟延迟」**这三条是按生产事故经验沉淀出来的机制，正好补 Data Khaos 现在最薄弱的「实时链路可信性」。建议按 P0 三项排期，其余按需取用。
