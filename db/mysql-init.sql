-- ============================================================
-- Data Khaos MySQL 初始化脚本（开发环境）
-- 数据库: MySQL 8.x (utf8mb4)
-- 生产方式请使用 db/dm8-init.sql（达梦 DM8）
-- ============================================================

CREATE DATABASE IF NOT EXISTS data_khaos DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE data_khaos;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. 系统表 - 认证与权限
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    username    VARCHAR(100) NOT NULL COMMENT '用户名',
    password    VARCHAR(255) NOT NULL COMMENT '密码(BCrypt加密)',
    real_name   VARCHAR(100) COMMENT '真实姓名',
    email       VARCHAR(200) COMMENT '邮箱',
    phone       VARCHAR(20)  COMMENT '手机号',
    avatar      VARCHAR(500) COMMENT '头像',
    status      TINYINT DEFAULT 1 COMMENT '状态 1:启用 0:禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    role_code   VARCHAR(100) NOT NULL COMMENT '角色编码',
    role_name   VARCHAR(200) NOT NULL COMMENT '角色名称',
    description VARCHAR(500) COMMENT '描述',
    status      TINYINT DEFAULT 1 COMMENT '状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id      VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    role_id VARCHAR(32) NOT NULL COMMENT '角色ID',
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_menu (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    parent_id   VARCHAR(32) COMMENT '父菜单ID',
    name        VARCHAR(200) NOT NULL COMMENT '菜单名称',
    path        VARCHAR(500) COMMENT '路由路径',
    component   VARCHAR(500) COMMENT '前端组件',
    permission  VARCHAR(200) COMMENT '权限标识(如 sys:user:list)',
    icon        VARCHAR(100) COMMENT '图标',
    type        TINYINT DEFAULT 1 COMMENT '类型 0:目录 1:菜单 2:按钮 3:API',
    sort_order  INT DEFAULT 0 COMMENT '排序',
    status      TINYINT DEFAULT 1 COMMENT '状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='菜单/资源表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    role_id         VARCHAR(32) NOT NULL COMMENT '角色ID',
    permission_id   VARCHAR(32) NOT NULL COMMENT '权限(资源)ID',
    permission_type VARCHAR(50) DEFAULT 'MENU' COMMENT '类型 MENU/API/DATA',
    UNIQUE KEY uk_role_perm (role_id, permission_id, permission_type)
) ENGINE=InnoDB COMMENT='角色权限关联表';

CREATE TABLE IF NOT EXISTS sys_organization (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    parent_id   VARCHAR(32) COMMENT '父组织ID',
    org_name    VARCHAR(200) NOT NULL COMMENT '组织名称',
    org_code    VARCHAR(100) COMMENT '组织编码',
    org_type    VARCHAR(50) COMMENT '类型 DEPT/COMPANY/GROUP',
    sort_order  INT DEFAULT 0 COMMENT '排序',
    status      TINYINT DEFAULT 1 COMMENT '状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='组织架构表';

CREATE TABLE IF NOT EXISTS sys_user_org (
    id         VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id    VARCHAR(32) NOT NULL COMMENT '用户ID',
    org_id     VARCHAR(32) NOT NULL COMMENT '组织ID',
    is_primary TINYINT DEFAULT 0 COMMENT '是否主组织',
    UNIQUE KEY uk_user_org (user_id, org_id)
) ENGINE=InnoDB COMMENT='用户组织关联表';

CREATE TABLE IF NOT EXISTS sys_org_permission (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    org_id          VARCHAR(32) NOT NULL COMMENT '组织ID',
    permission_id   VARCHAR(32) NOT NULL COMMENT '菜单/资源ID',
    permission_type VARCHAR(50) DEFAULT 'MENU' COMMENT '类型 MENU/API/DATA',
    UNIQUE KEY uk_org_perm (org_id, permission_id, permission_type)
) ENGINE=InnoDB COMMENT='组织部门权限关联表（部门授予的菜单权限，成员自动继承）';

CREATE TABLE IF NOT EXISTS sys_row_policy (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    policy_name     VARCHAR(200) NOT NULL COMMENT '策略名称',
    target_table    VARCHAR(200) NOT NULL COMMENT '目标表',
    expression      VARCHAR(1000) NOT NULL COMMENT '过滤表达式(支持 #{currentUserId}/#{currentOrgId})',
    expression_desc VARCHAR(500) COMMENT '表达式描述',
    role_id         VARCHAR(32) COMMENT '角色ID',
    user_id         VARCHAR(32) COMMENT '用户ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（支持按组绑定）',
    status          TINYINT DEFAULT 1 COMMENT '状态',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='行权限策略表';

CREATE TABLE IF NOT EXISTS sys_column_policy (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    policy_name   VARCHAR(200) NOT NULL COMMENT '策略名称',
    target_table  VARCHAR(200) NOT NULL COMMENT '目标表',
    column_name   VARCHAR(200) NOT NULL COMMENT '目标字段',
    mask_type     VARCHAR(50) DEFAULT 'MASK' COMMENT '脱敏方式 MASK/ENCRYPT/HIDE/PLAIN',
    mask_rule     VARCHAR(200) COMMENT '脱敏规则(如 left:3,right:4)',
    role_id       VARCHAR(32) COMMENT '角色ID',
    user_id       VARCHAR(32) COMMENT '用户ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（支持按组绑定）',
    status        TINYINT DEFAULT 1 COMMENT '状态',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='列权限策略表';

CREATE TABLE IF NOT EXISTS sys_table_permission (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    datasource_id    VARCHAR(32) COMMENT '数据源ID',
    database_name    VARCHAR(200) COMMENT '数据库',
    table_name       VARCHAR(200) COMMENT '表名',
    permission_type  VARCHAR(50) NOT NULL COMMENT '权限类型 SELECT/INSERT/UPDATE/DELETE/ALL',
    role_id          VARCHAR(32) COMMENT '角色ID',
    user_id          VARCHAR(32) COMMENT '用户ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（按组授权，成员自动继承）',
    grant_type       VARCHAR(50) DEFAULT 'ROLE' COMMENT '授予类型 ROLE/USER/PROJECT_GROUP',
    status           TINYINT DEFAULT 1 COMMENT '状态',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_tp_project_group (project_group_id)
) ENGINE=InnoDB COMMENT='表权限表';

-- 项目组（组织下的业务协作单元）
CREATE TABLE IF NOT EXISTS sg_project_group (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    org_id        VARCHAR(32)  NOT NULL COMMENT '所属组织/业务线ID',
    project_name  VARCHAR(200) NOT NULL COMMENT '项目组名称',
    project_code  VARCHAR(100) COMMENT '项目组编码',
    leader_id     VARCHAR(32) COMMENT '组长用户ID',
    status        TINYINT DEFAULT 1 COMMENT '状态 0:停用 1:启用',
    sort_order    INT DEFAULT 0 COMMENT '排序',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_pg_code (project_code),
    KEY idx_pg_org (org_id)
) ENGINE=InnoDB COMMENT='项目组表';

-- 项目组成员（人→项目组，含组内角色 + 主组标记）
CREATE TABLE IF NOT EXISTS sg_project_group_member (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) NOT NULL COMMENT '项目组ID',
    user_id          VARCHAR(32) NOT NULL COMMENT '用户ID',
    project_role_id  VARCHAR(32) COMMENT '组内角色ID',
    is_primary       TINYINT DEFAULT 0 COMMENT '是否主项目组 0:否 1:是',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_pgm (project_group_id, user_id),
    KEY idx_pgm_user (user_id)
) ENGINE=InnoDB COMMENT='项目组成员表';

-- 项目组角色（capability_flags 为能力位 JSON 数组；project_group_id 为空=全局模板）
CREATE TABLE IF NOT EXISTS sg_project_role (
    id               VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    org_id           VARCHAR(32) COMMENT '所属组织ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（空=全局模板）',
    role_name        VARCHAR(200) NOT NULL COMMENT '角色名称',
    role_code        VARCHAR(100) COMMENT '角色编码',
    capability_flags VARCHAR(1000) COMMENT '能力位标识集合(JSON数组，如 ["model:develop","report:develop"])',
    status           TINYINT DEFAULT 1 COMMENT '状态 0:停用 1:启用',
    sort_order       INT DEFAULT 0 COMMENT '排序',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_spr_group (project_group_id)
) ENGINE=InnoDB COMMENT='项目组角色表';

-- 项目组资源（组下绑定的开发任务/报表/表）
CREATE TABLE IF NOT EXISTS sg_project_group_resource (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) NOT NULL COMMENT '项目组ID',
    resource_type    VARCHAR(50) NOT NULL COMMENT '资源类型 TASK/REPORT/TABLE',
    resource_id      VARCHAR(32) NOT NULL COMMENT '资源ID',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_pgr (project_group_id, resource_type, resource_id)
) ENGINE=InnoDB COMMENT='项目组资源表';

-- ============================================================
-- 2. 业务表 - 审批流程
-- ============================================================

CREATE TABLE IF NOT EXISTS app_apply (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    applicant_id     VARCHAR(32) NOT NULL COMMENT '申请人ID',
    apply_type       VARCHAR(50) NOT NULL COMMENT '类型 TABLE/REPORT/DATASOURCE/MENU',
    target_id        VARCHAR(32) COMMENT '申请目标ID',
    target_name      VARCHAR(200) COMMENT '申请目标名称',
    reason           VARCHAR(1000) COMMENT '申请理由',
    status           TINYINT DEFAULT 0 COMMENT '状态 0:待审批 1:通过 2:驳回 3:已撤销',
    current_approver VARCHAR(32) COMMENT '当前审批人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='权限申请表';

CREATE TABLE IF NOT EXISTS app_approval_record (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    apply_id    VARCHAR(32) NOT NULL COMMENT '申请单ID',
    approver_id VARCHAR(32) NOT NULL COMMENT '审批人ID',
    action      TINYINT NOT NULL COMMENT '动作 1:通过 2:驳回 3:转交',
    comment     VARCHAR(1000) COMMENT '审批意见',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='审批记录表';

CREATE TABLE IF NOT EXISTS app_approval_flow (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    flow_name     VARCHAR(200) NOT NULL COMMENT '流程名称',
    apply_type    VARCHAR(50) NOT NULL COMMENT '申请类型',
    step_order    INT DEFAULT 1 COMMENT '步骤序号',
    approver_role VARCHAR(100) COMMENT '审批角色编码',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='审批流程定义表';

-- ============================================================
-- 3. 元数据表
-- ============================================================

CREATE TABLE IF NOT EXISTS meta_datasource (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    ds_name       VARCHAR(200) NOT NULL COMMENT '数据源名称',
    ds_type       VARCHAR(50) NOT NULL COMMENT '类型 HIVE/DORIS/TRANSWARP/CLICKHOUSE/MYSQL/DM8/POSTGRESQL',
    host          VARCHAR(200) COMMENT '主机',
    port          INT COMMENT '端口',
    database_name VARCHAR(200) COMMENT '默认库',
    username      VARCHAR(200) COMMENT '用户名',
    password      VARCHAR(500) COMMENT '密码(AES加密存储)',
    properties    TEXT COMMENT '扩展属性(JSON)',
    status        TINYINT DEFAULT 1 COMMENT '状态',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='数据源配置表';

CREATE TABLE IF NOT EXISTS meta_database (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    datasource_id VARCHAR(32) NOT NULL COMMENT '数据源ID',
    database_name VARCHAR(200) NOT NULL COMMENT '数据库名',
    description   VARCHAR(500) COMMENT '描述',
    sync_time     DATETIME COMMENT '同步时间',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_ds_db (datasource_id, database_name)
) ENGINE=InnoDB COMMENT='数据库信息表';

CREATE TABLE IF NOT EXISTS meta_table (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    database_id VARCHAR(32) NOT NULL COMMENT '数据库ID',
    table_name  VARCHAR(200) NOT NULL COMMENT '表名',
    table_type  VARCHAR(50) DEFAULT 'TABLE' COMMENT '类型 TABLE/VIEW',
    description VARCHAR(500) COMMENT '描述',
    row_count   BIGINT DEFAULT 0 COMMENT '行数',
    table_size  BIGINT DEFAULT 0 COMMENT '大小(字节)',
    sync_time   DATETIME COMMENT '同步时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_db_table (database_id, table_name)
) ENGINE=InnoDB COMMENT='表信息表';

CREATE TABLE IF NOT EXISTS meta_column (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    table_id        VARCHAR(32) NOT NULL COMMENT '表ID',
    column_name     VARCHAR(200) NOT NULL COMMENT '字段名',
    column_type     VARCHAR(100) COMMENT '字段类型',
    column_length   INT COMMENT '长度',
    column_scale    INT COMMENT '精度',
    is_nullable     TINYINT DEFAULT 1 COMMENT '是否可空',
    is_primary_key  TINYINT DEFAULT 0 COMMENT '是否主键',
    default_value   VARCHAR(500) COMMENT '默认值',
    description     VARCHAR(500) COMMENT '描述',
    sort_order      INT DEFAULT 0 COMMENT '排序',
    sensitive_level TINYINT DEFAULT 0 COMMENT '敏感级别 0:普通 1:敏感 2:高度敏感',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_table_col (table_id, column_name)
) ENGINE=InnoDB COMMENT='字段信息表';

CREATE TABLE IF NOT EXISTS meta_table_lineage (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    source_table_id VARCHAR(32) NOT NULL COMMENT '源表ID',
    target_table_id VARCHAR(32) NOT NULL COMMENT '目标表ID',
    source_column   VARCHAR(200) COMMENT '源字段',
    target_column   VARCHAR(200) COMMENT '目标字段',
    relation_type   VARCHAR(50) DEFAULT 'ETL' COMMENT '关系类型',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='表血缘关系表';

-- ============================================================
-- 4. 集市表
-- ============================================================

CREATE TABLE IF NOT EXISTS mart_model (
    id                 VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id   VARCHAR(32) COMMENT '项目组ID（权限隔离）',
    layer_id           VARCHAR(32) COMMENT '分层ID',
    model_name         VARCHAR(200) NOT NULL COMMENT '模型名称',
    model_code         VARCHAR(100) NOT NULL COMMENT '模型编码',
    model_type         VARCHAR(50) DEFAULT 'STAR' COMMENT '类型 STAR/SNOWFLAKE',
    datasource_id      VARCHAR(32) COMMENT '数据源ID',
    fact_table         VARCHAR(200) COMMENT '主事实表',
    description        VARCHAR(500) COMMENT '描述',
    status             TINYINT DEFAULT 0 COMMENT '状态 0:草稿 1:已发布 2:下线',
    version            INT DEFAULT 1 COMMENT '版本',
    create_time        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_model_code (model_code)
) ENGINE=InnoDB COMMENT='数据集市-语义模型定义表（按项目组权限隔离）';

CREATE TABLE IF NOT EXISTS mart_warehouse_layer (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    layer_code  VARCHAR(32) NOT NULL COMMENT '分层编码 ODS/DWD/DWS/ADS',
    layer_name  VARCHAR(100) NOT NULL COMMENT '分层名称',
    layer_desc  VARCHAR(500) COMMENT '分层说明',
    sort_order  INT DEFAULT 0 COMMENT '排序',
    status      TINYINT DEFAULT 1 COMMENT '状态 0:停用 1:启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_layer_code (layer_code)
) ENGINE=InnoDB COMMENT='数仓分层表（一线大厂标准：ODS/DWD/DWS/ADS）';

CREATE TABLE IF NOT EXISTS mart_metric (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（权限隔离）',
    metric_name VARCHAR(200) NOT NULL COMMENT '指标名称',
    metric_code VARCHAR(100) NOT NULL COMMENT '指标编码',
    metric_type VARCHAR(50) DEFAULT 'ATOMIC' COMMENT '类型 ATOMIC/DERIVED',
    expression  VARCHAR(1000) COMMENT '计算表达式',
    data_type   VARCHAR(50) DEFAULT 'BIGINT' COMMENT '数据类型',
    unit        VARCHAR(50) COMMENT '单位',
    category_id VARCHAR(32) COMMENT '分类ID',
    model_id    VARCHAR(32) COMMENT '模型ID',
    description VARCHAR(500) COMMENT '描述',
    status      TINYINT DEFAULT 1 COMMENT '状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_metric_code (metric_code)
) ENGINE=InnoDB COMMENT='指标定义表';

CREATE TABLE IF NOT EXISTS mart_dimension (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（权限隔离）',
    dim_name      VARCHAR(200) NOT NULL COMMENT '维度名称',
    dim_code      VARCHAR(100) NOT NULL COMMENT '维度编码',
    dim_type      VARCHAR(50) DEFAULT 'COMMON' COMMENT '类型 COMMON/TIME/ORG',
    model_id      VARCHAR(32) COMMENT '模型ID',
    source_table  VARCHAR(200) COMMENT '来源表',
    source_column VARCHAR(200) COMMENT '来源字段',
    description   VARCHAR(500) COMMENT '描述',
    status        TINYINT DEFAULT 1 COMMENT '状态',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_dim_code (dim_code)
) ENGINE=InnoDB COMMENT='维度定义表';

CREATE TABLE IF NOT EXISTS mart_dim_level (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    dim_id      VARCHAR(32) NOT NULL COMMENT '维度ID',
    level_name  VARCHAR(200) COMMENT '层级名称',
    level_column VARCHAR(200) COMMENT '层级字段',
    level_order INT DEFAULT 0 COMMENT '层级顺序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='维度层级表';

CREATE TABLE IF NOT EXISTS mart_model_rel (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（权限隔离）',
    model_id    VARCHAR(32) NOT NULL COMMENT '模型ID',
    fact_table  VARCHAR(200) NOT NULL COMMENT '事实表',
    dim_table   VARCHAR(200) NOT NULL COMMENT '维度表',
    join_key    VARCHAR(200) NOT NULL COMMENT '关联键',
    join_type   VARCHAR(50) DEFAULT 'INNER' COMMENT '关联类型 INNER/LEFT/RIGHT',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='模型关联关系表';

-- ============================================================
-- 4.5 数据质量稽核表
-- ============================================================

CREATE TABLE IF NOT EXISTS dquality_rule (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组ID（权限隔离，空=全局模板）',
    rule_code        VARCHAR(64) COMMENT '规则编码',
    rule_name        VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type        VARCHAR(32) NOT NULL COMMENT '规则类型 NOT_NULL/UNIQUE/VALUE_RANGE/CUSTOM_SQL/CUSTOM_PROBE',
    datasource_id    VARCHAR(32) COMMENT '数据源ID',
    database_name    VARCHAR(128) COMMENT '库',
    table_name       VARCHAR(128) COMMENT '表',
    column_name      VARCHAR(128) COMMENT '字段（表级规则可空）',
    rule_config      TEXT COMMENT '规则配置 JSON（阈值/表达式/自定义SQL）',
    weight           INT DEFAULT 1 COMMENT '权重（评分用）',
    alert_threshold  DECIMAL(5,2) DEFAULT 0 COMMENT '告警阈值（如空值率 0.05）',
    status           TINYINT DEFAULT 1 COMMENT '0停用 1启用',
    create_by        VARCHAR(32) COMMENT '创建人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_dqr_group (project_group_id),
    KEY idx_dqr_table (datasource_id, database_name, table_name)
) ENGINE=InnoDB COMMENT='数据质量-质量规则表';

CREATE TABLE IF NOT EXISTS dquality_task (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组隔离',
    task_name        VARCHAR(128) NOT NULL COMMENT '任务名称',
    rule_ids         TEXT COMMENT '关联规则ID集合（JSON数组）',
    cron_expr        VARCHAR(64) COMMENT '周期表达式（空=一次性/手动）',
    status           TINYINT DEFAULT 1 COMMENT '0停用 1启用',
    create_by        VARCHAR(32) COMMENT '创建人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_dqt_group (project_group_id)
) ENGINE=InnoDB COMMENT='数据质量-质量任务表';

CREATE TABLE IF NOT EXISTS dquality_snapshot (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_group_id VARCHAR(32) COMMENT '项目组隔离',
    task_id          VARCHAR(32) COMMENT '关联任务',
    datasource_id    VARCHAR(32) COMMENT '数据源ID',
    database_name    VARCHAR(128) COMMENT '库',
    table_name       VARCHAR(128) COMMENT '表',
    score            DECIMAL(5,2) COMMENT '质量评分 0-100',
    rule_total       INT DEFAULT 0 COMMENT '规则总数',
    rule_pass        INT DEFAULT 0 COMMENT '通过数',
    rule_fail        INT DEFAULT 0 COMMENT '失败数',
    detail           TEXT COMMENT '明细 JSON（各规则结果）',
    cost_ms          BIGINT DEFAULT 0 COMMENT '耗时(ms)',
    trigger_type     VARCHAR(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULE',
    create_by        VARCHAR(32) COMMENT '创建人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_dqs_group (project_group_id),
    KEY idx_dqs_task (task_id),
    KEY idx_dqs_table (datasource_id, database_name, table_name)
) ENGINE=InnoDB COMMENT='数据质量-稽核快照表（每次执行）';

CREATE TABLE IF NOT EXISTS dquality_rule_result (
    id           VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    snapshot_id  VARCHAR(32) NOT NULL COMMENT '关联快照',
    rule_id      VARCHAR(32) COMMENT '规则ID',
    passed       TINYINT DEFAULT 0 COMMENT '0失败 1通过',
    actual_value DECIMAL(20,4) COMMENT '实际值（如空值率）',
    threshold    DECIMAL(5,2) COMMENT '阈值',
    sample_rows  TEXT COMMENT '违规样本（前 N 行 JSON）',
    message      VARCHAR(500) COMMENT '结果说明',
    KEY idx_dqrr_snapshot (snapshot_id)
) ENGINE=InnoDB COMMENT='数据质量-规则执行结果表';

-- ============================================================
-- 5. 调度表
-- ============================================================

CREATE TABLE IF NOT EXISTS schedule_job (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    job_name        VARCHAR(200) NOT NULL COMMENT '任务名称',
    job_type        VARCHAR(50) NOT NULL COMMENT '类型 SYNC/SQL/REFRESH/PUSH',
    job_group       VARCHAR(100) COMMENT '分组',
    cron_expression VARCHAR(100) COMMENT 'Cron表达式',
    datasource_id   VARCHAR(32) COMMENT '数据源ID',
    target_sql      TEXT COMMENT '执行SQL',
    target_table    VARCHAR(200) COMMENT '目标表',
    params          TEXT COMMENT '参数(JSON)',
    status          TINYINT DEFAULT 0 COMMENT '状态 0:停用 1:启用',
    retry_count     INT DEFAULT 0 COMMENT '重试次数',
    retry_interval  INT DEFAULT 60 COMMENT '重试间隔(秒)',
    timeout         INT DEFAULT 3600 COMMENT '超时(秒)',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='任务定义表';

CREATE TABLE IF NOT EXISTS schedule_job_log (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    job_id        VARCHAR(32) NOT NULL COMMENT '任务ID',
    status        TINYINT COMMENT '状态 0:运行中 1:成功 2:失败',
    start_time    DATETIME COMMENT '开始时间',
    end_time      DATETIME COMMENT '结束时间',
    duration_ms   BIGINT COMMENT '耗时(毫秒)',
    error_message TEXT COMMENT '错误信息',
    result_rows   INT DEFAULT 0 COMMENT '结果行数',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='任务执行日志表';

CREATE TABLE IF NOT EXISTS schedule_job_dep (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    job_id      VARCHAR(32) NOT NULL COMMENT '任务ID',
    dep_job_id  VARCHAR(32) NOT NULL COMMENT '依赖任务ID',
    dep_type    VARCHAR(50) DEFAULT 'HARD' COMMENT '依赖类型 HARD/SOFT',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_job_dep (job_id, dep_job_id)
) ENGINE=InnoDB COMMENT='任务依赖关系表';

-- ============================================================
-- 6. 通知表
-- ============================================================

CREATE TABLE IF NOT EXISTS notify_template (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    template_code   VARCHAR(100) NOT NULL COMMENT '模板编码',
    template_name   VARCHAR(200) NOT NULL COMMENT '模板名称',
    channel         VARCHAR(50) NOT NULL COMMENT '渠道 MAIL/SITE/WECHAT/SMS',
    title_template  VARCHAR(500) COMMENT '标题模板',
    content_template TEXT COMMENT '内容模板',
    status          TINYINT DEFAULT 1 COMMENT '状态',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_tpl_code (template_code)
) ENGINE=InnoDB COMMENT='消息模板表';

CREATE TABLE IF NOT EXISTS notify_record (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    template_id   VARCHAR(32) COMMENT '模板ID',
    receiver_id   VARCHAR(32) COMMENT '接收人ID',
    receiver_type VARCHAR(50) DEFAULT 'USER' COMMENT '类型 USER/ROLE/ORG',
    channel       VARCHAR(50) COMMENT '渠道',
    title         VARCHAR(500) COMMENT '标题',
    content       TEXT COMMENT '内容',
    status        TINYINT DEFAULT 0 COMMENT '状态 0:待发送 1:已发送 2:发送失败',
    send_time     DATETIME COMMENT '发送时间',
    error_message VARCHAR(500) COMMENT '错误信息',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='推送记录表';

CREATE TABLE IF NOT EXISTS notify_subscription (
    id             VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id        VARCHAR(32) NOT NULL COMMENT '用户ID',
    subscribe_type VARCHAR(50) NOT NULL COMMENT '类型 REPORT/METRIC/JOB',
    target_id      VARCHAR(32) COMMENT '目标ID',
    channel        VARCHAR(50) DEFAULT 'SITE' COMMENT '渠道',
    status         TINYINT DEFAULT 1 COMMENT '状态',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_sub (user_id, subscribe_type, target_id, channel)
) ENGINE=InnoDB COMMENT='用户订阅表';

-- ============================================================
-- 7. 查询历史表
-- ============================================================

CREATE TABLE IF NOT EXISTS query_history (
    id             VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id        VARCHAR(32) COMMENT '用户ID',
    datasource_id  VARCHAR(32) COMMENT '数据源ID',
    database_name  VARCHAR(200) COMMENT '数据库',
    sql_text       TEXT COMMENT 'SQL文本',
    status         TINYINT DEFAULT 1 COMMENT '状态 1:成功 0:失败',
    cost_ms        BIGINT COMMENT '耗时(毫秒)',
    row_count      INT DEFAULT 0 COMMENT '结果行数',
    error_message  VARCHAR(1000) COMMENT '错误信息',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='查询历史表';

-- ============================================================
-- 8. 可视化表
-- ============================================================

CREATE TABLE IF NOT EXISTS visual_dashboard (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    name             VARCHAR(200) NOT NULL COMMENT '仪表板名称',
    description      VARCHAR(500) COMMENT '描述',
    layout           TEXT COMMENT '布局配置(JSON)',
    refresh_interval INT DEFAULT 60 COMMENT '刷新间隔(秒)',
    status           TINYINT DEFAULT 1 COMMENT '状态 0:停用 1:草稿 2:已上线',
    version          INT DEFAULT 0 COMMENT '当前版本号',
    create_by        VARCHAR(32) COMMENT '创建人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='仪表板表';

CREATE TABLE IF NOT EXISTS visual_dashboard_version (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    dashboard_id     VARCHAR(32) NOT NULL COMMENT '仪表板ID',
    version          INT DEFAULT 1 COMMENT '版本号',
    name             VARCHAR(200) COMMENT '仪表板名称快照',
    description      VARCHAR(500) COMMENT '描述快照',
    layout           TEXT COMMENT '布局快照(JSON)',
    refresh_interval INT DEFAULT 60 COMMENT '刷新间隔快照',
    items_json       LONGTEXT COMMENT '组件快照(JSON数组)',
    remark           VARCHAR(500) COMMENT '发布说明',
    create_by        VARCHAR(32) COMMENT '发布人',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间'
) ENGINE=InnoDB COMMENT='仪表板版本快照表';
CREATE INDEX idx_dv_dashboard ON visual_dashboard_version(dashboard_id);

CREATE TABLE IF NOT EXISTS visual_dashboard_item (
    id            VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    dashboard_id  VARCHAR(32) NOT NULL COMMENT '仪表板ID',
    board_id      VARCHAR(32) COMMENT '分析板ID',
    title         VARCHAR(200) COMMENT '组件标题',
    chart_type    VARCHAR(50) DEFAULT 'TABLE' COMMENT '图表类型 BAR/LINE/PIE/SCATTER/HEATMAP/AREA/GAUGE/TREEMAP/BOXPLOT/MAP/TABLE/NUMBER',
    datasource_id VARCHAR(32) COMMENT '数据源ID',
    query_sql     TEXT COMMENT '查询SQL',
    drill_sql     TEXT COMMENT '下钻明细SQL(可选)',
    config        TEXT COMMENT '组件配置(JSON)',
    pos_x         INT DEFAULT 0 COMMENT 'X坐标',
    pos_y         INT DEFAULT 0 COMMENT 'Y坐标',
    width         INT DEFAULT 4 COMMENT '宽度',
    height        INT DEFAULT 4 COMMENT '高度',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='仪表板组件表';

CREATE TABLE IF NOT EXISTS visual_board (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    dashboard_id     VARCHAR(32) NOT NULL COMMENT '仪表板ID',
    board_name       VARCHAR(200) NOT NULL COMMENT '分析板标题',
    subtitle         VARCHAR(500) COMMENT '副标题',
    icon             VARCHAR(100) COMMENT '图标',
    board_type       VARCHAR(50) DEFAULT 'ANALYSIS' COMMENT '类型 ANALYSIS/CUSTOM',
    layout           TEXT COMMENT '板块样式与布局配置(JSON)',
    filters          TEXT COMMENT '分析板独立筛选配置(JSON)',
    link_global      TINYINT DEFAULT 1 COMMENT '是否联动全局筛选 1:联动 0:独立',
    refresh_interval INT DEFAULT 60 COMMENT '自动刷新周期(秒)',
    collapse         TINYINT DEFAULT 0 COMMENT '是否折叠 0:展开 1:折叠',
    locked           TINYINT DEFAULT 0 COMMENT '是否锁定布局',
    sort_order       INT DEFAULT 0 COMMENT '排序',
    status           TINYINT DEFAULT 1 COMMENT '状态',
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='分析板表（仪表板内嵌套子业务模块）';
CREATE INDEX idx_board_dashboard ON visual_board(dashboard_id);

ALTER TABLE visual_dashboard_version ADD COLUMN boards_json LONGTEXT COMMENT '分析板快照(JSON数组)' AFTER items_json;

-- 8.4 即席查询收藏表
CREATE TABLE IF NOT EXISTS visual_adhoc_query (
    id           VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    name         VARCHAR(200) NOT NULL COMMENT '查询名称',
    datasource_id VARCHAR(32) COMMENT '数据源ID',
    sql_text     TEXT COMMENT '查询SQL（支持 ${param} 占位符）',
    params_json  TEXT COMMENT '默认参数（JSON: Map<String,Object>）',
    folder       VARCHAR(100) COMMENT '分组/文件夹',
    create_by    VARCHAR(32) COMMENT '创建人',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='即席查询收藏表';
CREATE INDEX idx_ahq_user ON visual_adhoc_query(create_by);

-- 8.5 即席查询执行历史表
CREATE TABLE IF NOT EXISTS visual_adhoc_history (
    id           VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    adhoc_id     VARCHAR(32) COMMENT '关联收藏查询ID（直接执行为空）',
    user_id      VARCHAR(32) COMMENT '执行用户ID',
    datasource_id VARCHAR(32) COMMENT '数据源ID',
    sql_text     TEXT COMMENT '实际执行SQL（已解析参数）',
    status       TINYINT DEFAULT 1 COMMENT '1:成功 0:失败',
    cost_ms      BIGINT DEFAULT 0 COMMENT '耗时(ms)',
    row_count    INT DEFAULT 0 COMMENT '结果行数',
    error_message VARCHAR(1000) COMMENT '错误信息',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='即席查询执行历史表';
CREATE INDEX idx_ahh_user ON visual_adhoc_history(user_id);
CREATE INDEX idx_ahh_adhoc ON visual_adhoc_history(adhoc_id);

-- ============================================================
-- 9. 初始数据
-- ============================================================

-- 默认管理员 admin / password
INSERT INTO sys_user (id, username, password, real_name, status) VALUES
('1', 'admin', '$2b$10$hdjujcTK4Qq.n14B3Tfgi.8Kci/7O2aGGtc4.ope8TWyYq6/GNEqO', '系统管理员', 1);

-- 默认角色
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
('1', 'SUPER_ADMIN', '超级管理员', '拥有所有权限'),
('2', 'ADMIN', '管理员', '系统管理权限'),
('3', 'USER', '普通用户', '基本数据访问权限');

-- 默认管理员角色
INSERT INTO sys_user_role (id, user_id, role_id) VALUES ('1', '1', '1');

-- 默认组织
INSERT INTO sys_organization (id, parent_id, org_name, org_code, org_type) VALUES
('1', NULL, '数据混沌科技', 'DK_GROUP', 'GROUP'),
('11', '1', '数据平台部', 'DK_DATA', 'DEPT'),
('12', '1', '研发部', 'DK_RD', 'DEPT');

INSERT INTO sys_user_org (id, user_id, org_id, is_primary) VALUES ('1', '1', '11', 1);

-- 默认菜单
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, icon, type, sort_order) VALUES
('1', NULL, '系统管理', '/system', 'Layout', NULL, 'Setting', 0, 1),
('11', '1', '用户管理', '/system/user', 'system/user/index', 'system:user:list', 'User', 1, 1),
('12', '1', '角色管理', '/system/role', 'system/role/index', 'system:role:list', 'Avatar', 1, 2),
('13', '1', '菜单管理', '/system/menu', 'system/menu/index', 'system:menu:list', 'Menu', 1, 3),
('14', '1', '组织管理', '/system/org', 'system/org/index', 'system:org:list', 'OfficeBuilding', 1, 4),
('2', NULL, '数据管理', '/data', 'Layout', NULL, 'DataLine', 0, 2),
('21', '2', '数据源管理', '/data/datasource', 'data/datasource/index', 'data:datasource:list', 'Connection', 1, 1),
('22', '2', '元数据管理', '/data/metadata', 'data/metadata/index', 'data:metadata:list', 'Files', 1, 2),
('23', '2', '数据集市', '/data/mart', 'data/mart/index', 'data:mart:list', 'Grid', 1, 3),
('3', NULL, '查询分析', '/query', 'Layout', NULL, 'Search', 0, 3),
('31', '3', 'SQL查询', '/query/sql', 'query/sql/index', 'query:sql:execute', 'EditPen', 1, 1),
('32', '3', '仪表板', '/query/dashboard', 'query/dashboard/index', 'query:dashboard:view', 'PieChart', 1, 2),
('33', '3', '分析板', '/query/analysis', 'query/analysis/index', 'query:analysis:view', 'TrendCharts', 1, 3),
('34', '3', '即席分析', '/visual/adhoc', 'visual/adhoc/index', 'visual:adhoc:execute', 'Magic', 1, 4),
('35', '3', '数据集管理', '/visual/dataset', 'visual/dataset/index', 'visual:dataset:list', 'Grid', 1, 5),
('4', NULL, '运维管理', '/ops', 'Layout', NULL, 'Operation', 0, 4),
('41', '4', '任务调度', '/ops/schedule', 'ops/schedule/index', 'ops:schedule:list', 'Timer', 1, 1),
('42', '4', '消息通知', '/ops/notification', 'ops/notification/index', 'ops:notification:list', 'Bell', 1, 2),
('43', '4', '审批管理', '/ops/approval', 'ops/approval/index', 'ops:approval:list', 'Stamp', 1, 3);

-- 超级管理员绑定全部菜单权限
INSERT INTO sys_role_permission (id, role_id, permission_id, permission_type)
SELECT CONCAT('rp_', m.id), '1', m.id, 'MENU' FROM sys_menu m;

-- 默认审批流
INSERT INTO app_approval_flow (id, flow_name, apply_type, step_order, approver_role) VALUES
('1', '表权限审批', 'TABLE', 1, 'ADMIN'),
('2', '报表权限审批', 'REPORT', 1, 'ADMIN'),
('3', '数据源权限审批', 'DATASOURCE', 1, 'ADMIN');

-- 默认消息模板
INSERT INTO notify_template (id, template_code, template_name, channel, title_template, content_template) VALUES
('1', 'SITE_NOTIFY', '站内通知', 'SITE', '【数据混沌】${title}', '${content}'),
('2', 'TASK_FAIL', '任务失败告警', 'SITE', '【任务告警】${jobName} 执行失败', '任务 ${jobName} 于 ${time} 执行失败：${message}');

-- 默认示例仪表板
INSERT INTO visual_dashboard (id, name, description, refresh_interval, status, version, create_by) VALUES
('1', '平台总览', '平台运行指标总览', 60, 1, 0, '1');

-- ============================================================
-- 门户模块展示配置（可插拔模块）
-- 说明：module_key 与前端模块注册表(registry.ts)一一对应；
--       mandatory=1 为系统必须模块，管理员不可取消展示；
--       visible=1 显示 / 0 隐藏。管理员可配置全局展示。
-- ============================================================
CREATE TABLE IF NOT EXISTS module_display_config (
    module_key     VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '模块唯一标识',
    module_name    VARCHAR(128) NOT NULL COMMENT '模块展示名称',
    category       VARCHAR(32)  NOT NULL COMMENT '归属分类 ingress/dev/govern/asset/service/ops/system',
    category_name  VARCHAR(64)  COMMENT '分类展示名',
    icon           VARCHAR(64)  COMMENT '图标',
    path           VARCHAR(200) COMMENT '路由路径（空=待建设）',
    mandatory      TINYINT DEFAULT 0 COMMENT '1=系统必须，不可取消 0=可配置',
    visible        TINYINT DEFAULT 1 COMMENT '1=显示 0=隐藏',
    sort_order    INT DEFAULT 0 COMMENT '排序',
    created_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_mdc_cat (category)
) ENGINE=InnoDB COMMENT='门户模块展示配置表（可插拔模块开关）';

-- 模块种子数据（与前端 registry.ts 保持一致；mandatory=1 为系统必须模块）
INSERT INTO module_display_config (module_key, module_name, category, category_name, icon, path, mandatory, visible, sort_order) VALUES
-- 数据接入
('ds_list', '数据源管理', 'ingress', '数据接入', 'Connection', '/datasource/list', 1, 1, 1),
('ds_conn', '数据库连接配置', 'ingress', '数据接入', 'Connection', '/datasource/list', 0, 1, 2),
('ds_sync', '数据同步任务', 'ingress', '数据接入', 'DataLine', '/pipeline/task', 0, 1, 3),
('ds_realtime', '实时数据接入', 'ingress', '数据接入', NULL, NULL, 0, 1, 4),
('ds_offline', '离线数据采集', 'ingress', '数据接入', NULL, NULL, 0, 1, 5),
('ds_api', '接口数据接入', 'ingress', '数据接入', NULL, NULL, 0, 1, 6),
('ds_file', '文件数据导入', 'ingress', '数据接入', NULL, NULL, 0, 1, 7),
-- 数据开发
('dev_sql', 'SQL 开发编辑器', 'dev', '数据开发', 'EditPen', '/query/query', 1, 1, 1),
('dev_schedule', '任务调度管理', 'dev', '数据开发', NULL, '/schedule/job', 0, 1, 2),
('dev_cron', '定时任务配置', 'dev', '数据开发', NULL, '/schedule/job', 0, 1, 3),
('dev_script', '数据脚本管理', 'dev', '数据开发', NULL, NULL, 0, 1, 4),
('dev_workflow', '工作流编排', 'dev', '数据开发', NULL, NULL, 0, 1, 5),
('dev_monitor', '任务监控', 'dev', '数据开发', NULL, NULL, 0, 1, 6),
('dev_version', '脚本版本管理', 'dev', '数据开发', NULL, NULL, 0, 1, 7),
-- 数据治理
('gov_quality', '数据质量校验', 'govern', '数据治理', 'Odometer', '/dquality/rule', 0, 1, 1),
('gov_meta', '元数据管理', 'govern', '数据治理', NULL, '/metadata/structure', 0, 1, 2),
('gov_lineage', '数据血缘分析', 'govern', '数据治理', NULL, '/metadata/lineage', 0, 1, 3),
('gov_dict', '数据字典管理', 'govern', '数据治理', NULL, NULL, 0, 1, 4),
('gov_std', '数据标准配置', 'govern', '数据治理', NULL, NULL, 0, 1, 5),
('gov_mask', '数据脱敏管理', 'govern', '数据治理', NULL, NULL, 0, 1, 6),
('gov_dedup', '重复数据清洗', 'govern', '数据治理', NULL, NULL, 0, 1, 7),
-- 数据资产
('asset_table', '数据表资产', 'asset', '数据资产', NULL, '/metadata/structure', 0, 1, 1),
('asset_metric', '指标资产', 'asset', '数据资产', NULL, '/mart/metric', 0, 1, 2),
('asset_perm', '资产权限管理', 'asset', '数据资产', NULL, '/permission/table', 0, 1, 3),
('asset_catalog', '资产目录查询', 'asset', '数据资产', NULL, NULL, 0, 1, 4),
('asset_label', '标签资产', 'asset', '数据资产', NULL, NULL, 0, 1, 5),
('asset_hot', '资产热度分析', 'asset', '数据资产', NULL, NULL, 0, 1, 6),
('asset_search', '资产检索', 'asset', '数据资产', NULL, NULL, 0, 1, 7),
-- 数据服务
('svc_report', '报表服务', 'service', '数据服务', NULL, '/visual/dashboard', 0, 1, 1),
('svc_adhoc', '自助取数', 'service', '数据服务', NULL, '/visual/adhoc', 0, 1, 2),
('svc_market', '模型市场', 'service', '数据服务', NULL, '/mart/market', 0, 1, 3),
('svc_api', '数据接口服务', 'service', '数据服务', NULL, NULL, 0, 1, 4),
('svc_publish', 'API 发布管理', 'service', '数据服务', NULL, NULL, 0, 1, 5),
('svc_share', '数据共享服务', 'service', '数据服务', NULL, NULL, 0, 1, 6),
('svc_export', '数据导出服务', 'service', '数据服务', NULL, NULL, 0, 1, 7),
-- 数据监控与运维
('ops_monitor', '任务运行监控', 'ops', '监控运维', 'Monitor', NULL, 0, 1, 1),
('ops_alert', '数据告警中心', 'ops', '监控运维', NULL, '/notification/send', 0, 1, 2),
('ops_log', '日志查询', 'ops', '监控运维', NULL, NULL, 0, 1, 3),
('ops_resource', '系统资源监控', 'ops', '监控运维', NULL, NULL, 0, 1, 4),
('ops_perm', '权限管理', 'ops', '监控运维', NULL, '/permission/table', 0, 1, 5),
('ops_user', '用户管理', 'ops', '监控运维', NULL, '/system/user', 0, 1, 6),
('ops_audit', '操作审计', 'ops', '监控运维', NULL, NULL, 0, 1, 7),
-- 系统管理
('sys_user', '用户管理', 'system', '系统管理', 'User', '/system/user', 1, 1, 1),
('sys_role', '角色管理', 'system', '系统管理', 'Avatar', '/system/role', 1, 1, 2),
('sys_menu', '菜单管理', 'system', '系统管理', 'Menu', '/system/menu', 1, 1, 3),
('sys_org', '组织管理', 'system', '系统管理', 'OfficeBuilding', '/system/org', 1, 1, 4),
('sys_approval', '审批中心', 'system', '系统管理', NULL, '/approval/apply', 0, 1, 5),
('sys_notify', '通知中心', 'system', '系统管理', NULL, '/notification/template', 0, 1, 6)
ON DUPLICATE KEY UPDATE module_name = VALUES(module_name);

-- ============================================================
-- 数据管道（Data Pipeline）数据模型
-- 说明：管理面负责任务/实例/worker 状态入库；执行面为独立引擎。
-- ============================================================

-- 管道任务定义表
CREATE TABLE IF NOT EXISTS pipeline_task (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    task_name     VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_type     VARCHAR(32)  DEFAULT 'SYNC' COMMENT '任务类型 SYNC=同步 ETL=加工',
    engine        VARCHAR(32)  DEFAULT 'DB_SYNC' COMMENT '执行引擎 DB_SYNC/DATAX/SEATUNNEL',
    source_ds_id  VARCHAR(32)  COMMENT '源数据源ID',
    source_table  VARCHAR(128) COMMENT '源表',
    target_ds_id  VARCHAR(32)  COMMENT '目标数据源ID',
    target_table  VARCHAR(128) COMMENT '目标表',
    source_query  TEXT COMMENT '源查询(自定义SQL，选填)',
    field_mapping TEXT COMMENT '字段映射(JSON，选填)',
    config        TEXT COMMENT '引擎扩展配置(JSON)',
    cron_expr     VARCHAR(64)  COMMENT '定时表达式(空=仅手动)',
    project_group_id VARCHAR(32) COMMENT '归属项目组(业务线-项目组隔离)',
    status        TINYINT      DEFAULT 1 COMMENT '1=启用 0=停用',
    create_by     VARCHAR(32)  COMMENT '创建人',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_pt_name (task_name),
    KEY idx_pt_project_group (project_group_id),
    KEY idx_pt_status (status)
) ENGINE=InnoDB COMMENT='数据管道任务定义表';

-- 管道执行实例表
CREATE TABLE IF NOT EXISTS pipeline_instance (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    task_id       VARCHAR(32)  NOT NULL COMMENT '关联任务ID',
    engine        VARCHAR(32)  COMMENT '执行引擎',
    trigger_type  VARCHAR(16)  DEFAULT 'MANUAL' COMMENT '触发方式 MANUAL/CRON',
    status        TINYINT      DEFAULT 0 COMMENT '0=运行中 1=成功 2=失败',
    start_time    DATETIME     COMMENT '开始时间',
    end_time      DATETIME     COMMENT '结束时间',
    duration_ms   BIGINT       DEFAULT 0 COMMENT '耗时(毫秒)',
    `rows`        BIGINT       DEFAULT 0 COMMENT '影响行数',
    error_message TEXT         COMMENT '失败原因',
    worker        VARCHAR(64)  COMMENT '执行worker标识',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_pi_task (task_id),
    KEY idx_pi_status (status),
    KEY idx_pi_start (start_time)
) ENGINE=InnoDB COMMENT='数据管道执行实例表';

-- 管道执行 worker 注册表（可扩展引擎执行节点）
CREATE TABLE IF NOT EXISTS pipeline_worker (
    id             VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    worker_name    VARCHAR(128) NOT NULL COMMENT 'worker名称/地址',
    engines        VARCHAR(128) DEFAULT 'DB_SYNC' COMMENT '支持引擎，逗号分隔',
    status         TINYINT      DEFAULT 0 COMMENT '1=在线 0=离线',
    last_heartbeat DATETIME     COMMENT '最近心跳',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    KEY idx_pw_status (status)
) ENGINE=InnoDB COMMENT='数据管道worker注册表';

-- ============================================================
-- 用户画像模块
-- ============================================================

CREATE TABLE IF NOT EXISTS portrait_tag_category (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    name        VARCHAR(100) NOT NULL COMMENT '分类名称',
    code        VARCHAR(100) COMMENT '分类编码',
    sort_order  INT DEFAULT 0 COMMENT '排序号',
    status      TINYINT DEFAULT 1 COMMENT '状态 1:启用 0:禁用',
    deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除 0:正常 1:删除',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    create_by   VARCHAR(64) COMMENT '创建人',
    update_by   VARCHAR(64) COMMENT '更新人',
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB COMMENT='用户画像标签分类表';

CREATE TABLE IF NOT EXISTS portrait_tag (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    category_id   VARCHAR(32)  NOT NULL COMMENT '所属分类ID',
    name          VARCHAR(100) NOT NULL COMMENT '标签名称',
    code          VARCHAR(100) COMMENT '标签编码',
    tag_type      VARCHAR(20) DEFAULT 'STR' COMMENT '标签类型 BOOL/NUMBER/STR/ENUM',
    unit          VARCHAR(20) COMMENT '单位(数值类型)',
    enum_options  TEXT COMMENT '枚举可选值(JSON数组)',
    description   VARCHAR(500) COMMENT '标签说明',
    status        TINYINT DEFAULT 1 COMMENT '状态 1:启用 0:禁用',
    deleted       TINYINT DEFAULT 0 COMMENT '逻辑删除 0:正常 1:删除',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    create_by     VARCHAR(64) COMMENT '创建人',
    update_by     VARCHAR(64) COMMENT '更新人',
    KEY idx_category (category_id),
    UNIQUE KEY uk_code (category_id, code)
) ENGINE=InnoDB COMMENT='用户画像标签定义表';

CREATE TABLE IF NOT EXISTS portrait_user_tag (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_key    VARCHAR(100) NOT NULL COMMENT '用户唯一标识(业务用户ID)',
    user_name   VARCHAR(100) COMMENT '用户名称(冗余展示)',
    tag_id      VARCHAR(32) NOT NULL COMMENT '标签ID',
    tag_value   VARCHAR(200) COMMENT '标签值',
    tag_time    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '标签时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    create_by   VARCHAR(64) COMMENT '创建人',
    UNIQUE KEY uk_user_tag (user_key, tag_id),
    KEY idx_tag (tag_id)
) ENGINE=InnoDB COMMENT='用户画像标签值表';

INSERT INTO portrait_tag_category (id, name, code, sort_order, status, deleted) VALUES
  ('pc_basic', '基础属性', 'basic', 1, 1, 0),
  ('pc_value', '价值分层', 'value', 2, 1, 0),
  ('pc_behavior', '行为偏好', 'behavior', 3, 1, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO portrait_tag (id, category_id, name, code, tag_type, unit, enum_options, description, status, deleted) VALUES
  ('pt_gender', 'pc_basic', '性别', 'gender', 'ENUM', NULL, '["男","女","未知"]', '用户性别', 1, 0),
  ('pt_age_band', 'pc_basic', '年龄段', 'ageBand', 'ENUM', NULL, '["18以下","18-25","26-35","36-45","46以上"]', '用户年龄段', 1, 0),
  ('pt_level', 'pc_value', '用户层级', 'level', 'ENUM', NULL, '["普通","优质","高潜","VIP"]', '用户价值等级', 1, 0),
  ('pt_consume', 'pc_value', '累计消费额(元)', 'totalConsume', 'NUMBER', '元', NULL, '累计消费金额', 1, 0),
  ('pt_active', 'pc_behavior', '活跃度', 'activeLevel', 'BOOL', NULL, NULL, '是否活跃用户', 1, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

SET FOREIGN_KEY_CHECKS = 1;
