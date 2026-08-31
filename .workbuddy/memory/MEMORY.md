# Data Khaos 项目长期笔记

## 项目性质
国产化大数据基础设施全栈平台（Spring Boot 3 + MyBatis-Plus + Vue3/TS/Element Plus/ECharts + 达梦 DM8）。作者 dkailab，Apache-2.0 开源，采用 Open-Core 模式（社区版 + 企业版）。

## 已知架构事实（2026-08-31 实测，复核过）

### README 与代码不符（对外开源前必须修正）
- **RocketMQ 不存在**：README 声称基于 RocketMQ，实际 Java/XML/YML 零命中，仅出现在 README/versionPlanning/architecture/PROJECT_PLAN 四个 md。服务间全为同步 `RestTemplate`。
- **行/列级权限不落地**：README 列为第一梯队核心特性，实际 `SysRowPolicy`/`SysColumnPolicy` 仅在 permission 模块内 6 个文件出现，零外溢到查询链路。
- 注册中心 Eureka/Consul：盘点未发现相关依赖，待确认。

### 权限体系现状
`PermissionApiClient`（permission-api）只暴露 4 个方法，**全部是表级**：
`getUserPermission` / `getUserTablePermissions` / `checkTablePermission` / `grantTablePermission`。
**无任何行/列策略查询接口** → 行/列权限要落地，必须先扩 api 接口，再在查询链路做 SQL 改写。

### 模块成熟度（四档）
- **生产可用**：datasource（8 种 SPI，Spring Bean 自动注册，非 JDK SPI）、metadata（字段语义+字典+标准落标+检索增强+采集保护全落地）、mart、visual、query、dquality（5 类规则引擎真跑）、pipeline（JDBC+DataX+SeaTunnel 真同步）、workflow（真 DAG，含环检测/并行/重试）
- **基本可用**：auth（JWT+验证码，无 SSO）
- **关键缺失**：schedule（DAG 依赖不生效 + REFRESH/SYNC/PUSH 假成功 + 单实例内存态）、approval（仅 TABLE 自动授权）、notification（仅站内信+邮件）、gateway（无审计）
- **链路断裂**：permission（行列策略不落地）

### 代码质量
很干净，全项目仅 2 处 TODO：`WorkflowEdge:29`（条件表达式预留）、`JobExecutor:123`（三类任务预留）。另 `DatasetService:156` 恒定返回空列表。

## 关键文件位置
- `data-khaos-web/src/modules/registry.ts` — **数据工程功能清单本体**。六大分类 43 个功能点，`path` 为空表示待建设（当前 21 个）。新增功能在此注册即可，无需改 Layout/路由。
- `docs/roadmap-data-engineering.md` — 七~十二阶段补全路线图（本次产出）
- `docs/bi-tools-plan.md` — 元数据治理规划（D1~D7，D7 质量稽核留企业版）
- `docs/open-core-plan.md` — 社区版/企业版边界
- `db/init.sql`（DM8）、`db/mysql-init.sql`（MySQL）— 建表+菜单种子，**改表务必同步两个文件**

## 待决的产品边界冲突
`open-core-plan.md` 把**数据脱敏**和**操作审计**划为企业版，但 `registry.ts` 把它们列为社区版待建设项（gov_mask / ops_audit）。开工前必须先定边界。

## 开发约定
- 新增 DB 表需同时改 `db/init.sql`（达梦语法）和 `db/mysql-init.sql`，并加 `sys_menu` 菜单种子行。
- Maven 实际路径 `/Users/dk/apache-maven/bin/mvn`（非 workbuddy 内置路径），离线构建加 `-o`。
- 前端 `request.ts` 仅默认导出 `service` + 命名 `get/post/put/del`，**无命名 `service` 导出**。
- 加 `data-khaos-permission-api` 依赖即自动装配 `PermissionApiClient`（靠 `PermissionApiAutoConfiguration` + common 的 `lbRestTemplate`），无需手写 bean。
- vue-tsc 递归解析函数需显式返回类型注解，否则 TS7022/7023。
