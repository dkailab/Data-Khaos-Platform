# Data Khaos 数据库设计文档（达梦 DM8）

## 1. 设计规范

### 1.1 命名规范

| 规范项 | 规则 |
|--------|------|
| 表前缀 | sys_（系统）、app_（业务）、meta_（元数据）、mart_（集市）、schedule_（调度）、notify_（通知） |
| 表名 | 小写+下划线，如 `sys_user`、`sys_role_permission` |
| 字段名 | 小写+下划线，如 `create_time`、`role_code` |
| 主键 | 统一使用 `VARCHAR(32)`，通过雪花算法生成 |
| 时间字段 | 统一使用 `DATETIME` 类型，默认 `CURRENT_TIMESTAMP()` |

### 1.2 达梦适配要点

- 驱动类: `dm.jdbc.driver.DmDriver`
- 连接 URL: `jdbc:dm://<host>:<port>/<dbname>`
- 默认端口: 5236
- 标识符引用: 双引号 `"`（兼容模式）
- 分页: 使用 MyBatis-Plus 的 DmDialect

## 2. 核心表清单

### 2.1 系统表（sys_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| sys_user | 用户表 | auth |
| sys_role | 角色表 | auth |
| sys_user_role | 用户角色关联 | auth |
| sys_menu | 菜单/资源表 | permission |
| sys_role_permission | 角色权限关联 | permission |
| sys_organization | 组织架构表 | permission |
| sys_user_org | 用户组织关联 | permission |
| sys_row_policy | 行权限策略 | permission |
| sys_column_policy | 列权限策略 | permission |
| sys_table_permission | 表权限 | permission |

### 2.2 业务表（app_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| app_apply | 权限申请表 | approval |
| app_approval_record | 审批记录表 | approval |

### 2.3 元数据表（meta_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| meta_datasource | 数据源配置 | datasource |
| meta_database | 数据库信息 | metadata |
| meta_table | 表信息 | metadata |
| meta_column | 字段信息 | metadata |
| meta_table_lineage | 表血缘关系 | metadata |

### 2.4 集市表（mart_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| mart_model | 模型定义 | mart |
| mart_metric | 指标定义 | mart |
| mart_dimension | 维度定义 | mart |
| mart_dim_level | 维度层级 | mart |
| mart_model_rel | 模型关联关系 | mart |

### 2.5 调度表（schedule_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| schedule_job | 任务定义 | schedule |
| schedule_job_log | 任务执行日志 | schedule |
| schedule_job_dep | 任务依赖关系 | schedule |

### 2.6 通知表（notify_*）

| 表名 | 说明 | 归属模块 |
|------|------|---------|
| notify_template | 消息模板 | notification |
| notify_record | 推送记录 | notification |
| notify_subscription | 用户订阅 | notification |

## 元数据治理演示

先导入 [订单示例](../db/seed-demo.sql)，再导入[治理示例](../db/seed-governance.sql)。治理脚本提供渠道、类目、订单状态和敏感级别字典，以及金额、数量与枚举标准，可用来验证字段说明、字典绑定和落标检查。
