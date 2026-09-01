# Data Khaos 能力清单 v1.1

> 国产化大数据基础设施全栈平台
> 版本：**v1.1.0**
> 说明：本文档汇总平台当前已交付的功能与能力，作为功能基线基线回溯与验收依据。
> 基线文档：`docs/versionPlanning.md`（v1.0.0）

---

## 1. 版本信息

| 项 | 值 |
|----|----|
| 版本号 | v1.1.0 |
| 项目名 | Data Khaos（数据工程） |
| 定位 | 国产化大数据基础设施全栈平台 |
| 覆盖链路 | 数据接入 → 权限管控 → 元数据管理 → 数据建模 → 指标查询 → 可视化分析 → 调度推送 |
| 开源协议 | Apache License 2.0 |
| 仓库 | https://github.com/dkailab/Data-Khaos |

---

## 2. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│ 应用层：仪表板 | 分析板 | 数据门户 | 系统管理（Vue3 前端）      │
├──────────────────────────────────────────────────────────────┤
│ 服务层：auth | permission | approval | datasource | metadata │
│         mart | query | visual | schedule | notification      │
├──────────────────────────────────────────────────────────────┤
│ 数据层：数据集市 | 元数据中心 | 数据湖 | 数据建模              │
├──────────────────────────────────────────────────────────────┤
│ 数据接入层：星环 | Hive | Doris | ClickHouse | MySQL | PG ...│
├──────────────────────────────────────────────────────────────┤
│ 基础设施层：达梦 DM8 | MySQL | Redis | Nacos | RocketMQ      │
└──────────────────────────────────────────────────────────────┘
```

- 前端 → 后端：RESTful API（经网关统一暴露）
- 服务间同步调用：OpenFeign（各 `*-api` 共享契约模块）
- 服务间异步：RocketMQ（事件驱动）
- 安全：Gateway 校验 JWT → Auth 解析身份 → Permission 校验权限

---

## 3. 模块能力清单

### 3.1 公共与基础设施

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-common` | 统一返回 `R<T>`、异常体系、通用模型、JWT 工具、元数据上下文过滤器、SQL 审计工具、加解密工具 |
| `data-khaos-gateway` | 路由转发、JWT Token 校验、白名单、接口限流（主机端口 8099 → 容器 8080） |

### 3.2 认证与权限

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-auth` | 登录（验证码 + JWT 签发）、用户管理、角色管理 |
| `data-khaos-permission` | 菜单管理、组织架构管理（树形）、用户权限聚合、行级策略、列级脱敏策略、表权限（SELECT/INSERT/UPDATE/DELETE） |
| `data-khaos-approval` | 权限申请、审批（通过 / 驳回 / 转交）、申请列表；扩展 `MART` 模型订阅审批类型 |

> 权限体系：RBAC（用户-角色）+ 组织权限 + 表/行/列级数据权限 +「组织 → 项目组 → 人」三级模型隔离。

### 3.3 数据接入与元数据

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-datasource` | 数据源 CRUD、测试连接、元数据同步、SPI 可插拔连接器（星环 / Hive / Doris / ClickHouse / MySQL / PostgreSQL / Oracle / 达梦） |
| `data-khaos-metadata` | 元数据管理：库、表、字段全链路；**结构树 + 搜索 + 血缘分析**；字段治理（业务名 / 业务说明 / 敏感级 / 字典标注） |

### 3.4 建模与查询

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-mart` | 数据集市：标准数仓分层（ODS/DWD/DWS/ADS）、模型 / 指标 / 维度 CRUD、模型关联、项目组隔离、能力位校验（browse/develop/publish）、模型市场（订阅 / 预览） |
| `data-khaos-query` | SQL 查询平台：SQL 执行、查询历史、历史详情、**SQL 智能补全、SQL 健康诊断、执行计划分析**（见 §4） |

### 3.5 可视化

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-visual` | 仪表板 CRUD、发布（只读预览 `?preview=1`）、版本管理与回滚；分析板 CRUD、即席查询（Ad-hoc）、下钻分析（Drill）、明暗主题双支持 |

### 3.6 调度与推送

| 模块 | 能力说明 |
|------|---------|
| `data-khaos-schedule` | 调度任务 CRUD、手动运行、执行日志、任务依赖（DAG）管理 |
| `data-khaos-notification` | 推送模板、订阅管理、发送、推送记录 |

### 3.7 前端工程

| 工程 | 能力说明 |
|------|---------|
| `data-khaos-web` | Vue3 + TypeScript + Element Plus + ECharts + Pinia + Vite 单页应用 |

前端功能模块：
- 登录 / 系统管理（用户、角色、菜单、组织）
- 权限管理（表权限、行策略、列策略）
- 数据源、元数据（搜索 / 结构 / 血缘 / 字段治理）
- 数据集市（模型市场 / 模型 / 指标 / 维度）
- 查询工作台（含 SQL 智能补全、诊断、执行计划）
- 可视化（仪表板 / 分析板，含拖拽编辑、发布、版本、下钻）
- 调度任务、通知推送、审批流

---

## 4. OnseSQL 智能查询能力（v1.1 新增）

基于 Codemirror 6 + JSQLParser 构建的增强查询工作台，覆盖补全、诊断、执行计划、Schema 懒加载四大能力。

### 4.1 智能补全
- **表限定符列补全**：`t.` 触发该表字段提示
- **无限定符列补全**：输入列名前缀直接提示作用域表字段
- **函数与关键字补全**：内置常用函数与 SQL 关键字
- **作用域解析**：基于完整 SQL 解析（JSQLParser）提取表别名映射，跨 FROM/JOIN 子句准确补全

### 4.2 SQL 健康诊断（「诊断」tab）
| 规则编码 | 检测项 | 默认级别 |
|----------|--------|---------|
| `SELECT_STAR` | `SELECT *` 返回全列浪费 IO | error |
| `NO_WHERE` | 查询缺少 WHERE 条件 | warning |
| `FULL_TABLE_SCAN` | 无 WHERE 且无 LIMIT 全表扫描 | error |
| `JOIN_NO_ON` | JOIN 缺少 ON 关联条件（笛卡尔积风险） | warning |
| `IMPLICIT_CONVERSION` | 不同列类型等值/数值列与字符串字面量比较，索引失效风险 | warning / error |

> 规则基于 JSQLParser 语法结构判断；隐式转换在提供数据源时按列类型比对（支持表别名）。解析失败的 SQL 回退到文本级正则兜底。

### 4.3 执行计划 EXPLAIN（「执行计划」tab）
- 对目标 SQL 执行 `EXPLAIN`，结果区新增独立 tab
- 展示**访问类型（type）、扫描行数（rows）、索引（key / key_len）**
- `type` 列徽标着色：`ALL` 全表扫描醒目提示
- `key` 有值 / 无值分别高亮（走没走索引）

### 4.4 Schema 树懒加载 + 元数据标注
- **懒加载**：初始化只拉取表名（`/hints`），点击表再请求字段（`/columns`），消除一次全量拉取的 N+1 开销
- **字段元数据标注**（来自 `/meta/table-columns`）：
  - 业务名（bizName）
  - 敏感级（高敏 / 敏感 tag）
  - 字典标记（dict tag）

### 4.5 明暗主题适配
- 全局 CSS 变量 + `.theme-dark` class 动态切换
- 编辑器主题跟随全局主题（oneDark 深色 / 默认浅色一键切换）

---

## 5. 技术栈

| 分类 | 选型 |
|------|------|
| 后端框架 | Spring Boot 3.2.0 / Spring Cloud 2023.0.0 / Spring Cloud Alibaba 2023.0.3.3 |
| 语言 / 构建 | Java 17 / Maven 多模块 |
| ORM | MyBatis-Plus 3.5.5（Spring Boot 3 starter） |
| 数据库 | 达梦 DM8（生产）/ MySQL 8（开发） |
| 注册中心 / 网关 | Nacos / Spring Cloud Gateway |
| SQL 解析 | JSqlParser（智能补全 / 诊断作用域与类型推断） |
| 编辑器 | CodeMirror 6 + `@codemirror/lang-sql` + `theme-one-dark` |
| 工具 / 文档 | Hutool 5.8.25 / Knife4j 4.4.0 |
| 前端 | Vue 3.4 + TypeScript + Element Plus 2.7 + ECharts 5.5 + Pinia + Vite 5 |
| 部署 | Docker / Docker Compose |

---

## 6. 服务端口

| 服务 | 端口 |
|------|------|
| data-khaos-gateway | 8099（主机）/ 8080（容器） |
| data-khaos-auth | 8081 |
| data-khaos-permission | 8082 |
| data-khaos-approval | 8083 |
| data-khaos-datasource | 8084 |
| data-khaos-metadata | 8085 |
| data-khaos-mart | 8086 |
| data-khaos-query | 8087 |
| data-khaos-visual | 8088 |
| data-khaos-schedule | 8089 |
| data-khaos-notification | 8090 |
| data-khaos-web（Vite 开发） | 5173 |

---

## 7. 部署与运维

- 开发：`cd docker && docker compose up -d --build` 一键拉起；`smoke-test.sh` 冒烟测试
- 前端：`cd data-khaos-web && npm install && npm run dev`（代理到网关 8099）
- 生产：`mvn clean install -Pprod`（引入达梦驱动并激活 dm8 配置）
- 数据库脚本：`db/init.sql`、`db/mysql-init.sql`、`db/seed-dashboards.sql`、`db/seed-demo.sql`

---

## 8. 版本变更记录

### v1.1.0（2026-08）
**核心新增：OnseSQL 查询能力增强**
1. **SQL 智能补全**：表限定符 / 无限定符列补全、函数与关键字补全、基于完整 SQL 的作用域表解析。
2. **SQL 健康诊断**：`select *`、缺 WHERE、全表扫描、JOIN 无 ON、隐式类型转换 →「诊断」tab。
3. **执行计划 EXPLAIN**：结果区新增「执行计划」tab，展示访问类型 / 扫描行数 / 索引使用，全表扫描醒目提示。
4. **Schema 树懒加载 + 元数据**：点表再取字段，标注业务名 / 字典 / 敏感级，消除 N+1 全量拉取。
5. **明暗主题适配**：全局 CSS 变量统一主题，编辑器跟随全局深浅色切换。

**涉及模块：**
- `data-khaos-query` / `data-khaos-query`（controller/service/dto）
- `data-khaos-metadata`（`/table-columns` 字段元数据接口）
- `data-khaos-datasource-api`（ColumnInfo DTO）
- `data-khaos-web`（OnesqlWorkbench.vue + onesql.ts 等 API）

---

### v1.0.0（2026-08 基线）
见 `docs/versionPlanning.md` 完整基线，包含数仓模型市场、项目组权限隔离、能力位校验、模型订阅审批、姿态文档等。

---

*Data Khaos © 2026 dkailab · Licensed under Apache 2.0*