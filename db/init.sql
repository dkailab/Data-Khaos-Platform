-- ============================================================
-- Data Khaos 达梦数据库初始化脚本
-- 作者: dkailab
-- 数据库: 达梦 DM8
-- ============================================================

-- 创建表空间（根据实际环境调整路径）
-- CREATE TABLESPACE data_khaos DATAFILE '/dm8/data/data_khaos.dbf' SIZE 1024M;

-- ============================================================
-- 1. 系统表 - 认证与权限
-- ============================================================

-- 1.1 用户表
CREATE TABLE sys_user (
    id          VARCHAR(32) PRIMARY KEY,
    username    VARCHAR(100) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    real_name   VARCHAR(100),
    email       VARCHAR(200),
    phone       VARCHAR(20),
    avatar      VARCHAR(500),
    status      TINYINT DEFAULT 1,  -- 1:启用 0:禁用
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_user IS '用户表';
COMMENT ON COLUMN sys_user.id IS '主键ID';
COMMENT ON COLUMN sys_user.username IS '用户名';
COMMENT ON COLUMN sys_user.password IS '密码（加密存储）';
COMMENT ON COLUMN sys_user.real_name IS '真实姓名';
COMMENT ON COLUMN sys_user.email IS '邮箱';
COMMENT ON COLUMN sys_user.phone IS '手机号';
COMMENT ON COLUMN sys_user.status IS '状态 1:启用 0:禁用';

-- 1.2 角色表
CREATE TABLE sys_role (
    id          VARCHAR(32) PRIMARY KEY,
    role_code   VARCHAR(100) NOT NULL UNIQUE,
    role_name   VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    status      TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_role IS '角色表';
COMMENT ON COLUMN sys_role.role_code IS '角色编码';
COMMENT ON COLUMN sys_role.role_name IS '角色名称';

-- 1.3 用户角色关联表
CREATE TABLE sys_user_role (
    id          VARCHAR(32) PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    role_id     VARCHAR(32) NOT NULL,
    UNIQUE(user_id, role_id)
);

COMMENT ON TABLE sys_user_role IS '用户角色关联表';

-- 1.4 菜单/资源表
CREATE TABLE sys_menu (
    id          VARCHAR(32) PRIMARY KEY,
    parent_id   VARCHAR(32),
    name        VARCHAR(200) NOT NULL,
    path        VARCHAR(500),
    component   VARCHAR(500),
    permission  VARCHAR(200),
    icon        VARCHAR(100),
    type        TINYINT DEFAULT 1,  -- 0:目录 1:菜单 2:按钮 3:API
    sort_order  INT DEFAULT 0,
    status      TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_menu IS '菜单/资源表';
COMMENT ON COLUMN sys_menu.parent_id IS '父菜单ID';
COMMENT ON COLUMN sys_menu.permission IS '权限标识符（如 sys:user:list）';
COMMENT ON COLUMN sys_menu.type IS '类型 0:目录 1:菜单 2:按钮 3:API';

-- 1.5 角色权限关联表
CREATE TABLE sys_role_permission (
    id            VARCHAR(32) PRIMARY KEY,
    role_id       VARCHAR(32) NOT NULL,
    permission_id VARCHAR(32) NOT NULL,
    permission_type VARCHAR(50) DEFAULT 'MENU',  -- MENU / API / DATA
    UNIQUE(role_id, permission_id, permission_type)
);

COMMENT ON TABLE sys_role_permission IS '角色权限关联表';

-- 1.6 组织架构表
CREATE TABLE sys_organization (
    id          VARCHAR(32) PRIMARY KEY,
    parent_id   VARCHAR(32),
    org_name    VARCHAR(200) NOT NULL,
    org_code    VARCHAR(100),
    org_type    VARCHAR(50),         -- DEPT / COMPANY / GROUP
    sort_order  INT DEFAULT 0,
    status      TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_organization IS '组织架构表';

-- 1.7 用户组织关联表
CREATE TABLE sys_user_org (
    id      VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    org_id  VARCHAR(32) NOT NULL,
    is_primary TINYINT DEFAULT 0,    -- 是否主组织
    UNIQUE(user_id, org_id)
);

COMMENT ON TABLE sys_user_org IS '用户组织关联表';

-- 1.8 组织权限关联表（部门-菜单权限）
CREATE TABLE sys_org_permission (
    id              VARCHAR(32) PRIMARY KEY,
    org_id          VARCHAR(32) NOT NULL,       -- 组织ID
    permission_id   VARCHAR(32) NOT NULL,       -- 菜单/资源ID
    permission_type VARCHAR(50) DEFAULT 'MENU', -- MENU/API/DATA
    UNIQUE(org_id, permission_id, permission_type)
);

COMMENT ON TABLE sys_org_permission IS '组织部门权限关联表（部门授予的菜单权限，成员自动继承）';

-- 1.9 行权限策略表
CREATE TABLE sys_row_policy (
    id              VARCHAR(32) PRIMARY KEY,
    policy_name     VARCHAR(200) NOT NULL,
    target_table    VARCHAR(200) NOT NULL,     -- 目标表
    expression      VARCHAR(1000) NOT NULL,    -- 过滤表达式（如 org_id = #{currentOrgId}）
    expression_desc VARCHAR(500),              -- 表达式描述
    role_id         VARCHAR(32),
    user_id         VARCHAR(32),
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_row_policy IS '行权限策略表';
COMMENT ON COLUMN sys_row_policy.expression IS '过滤表达式，支持 #{currentUserId}, #{currentOrgId} 等变量';

-- 1.9 列权限策略表
CREATE TABLE sys_column_policy (
    id              VARCHAR(32) PRIMARY KEY,
    policy_name     VARCHAR(200) NOT NULL,
    target_table    VARCHAR(200) NOT NULL,     -- 目标表
    column_name     VARCHAR(200) NOT NULL,     -- 目标字段
    mask_type       VARCHAR(50) DEFAULT 'MASK',-- 脱敏方式: MASK(掩码) / ENCRYPT(加密) / HIDE(隐藏) / PLAIN(明文)
    mask_rule       VARCHAR(200),              -- 脱敏规则（如 left:3,right:4）
    role_id         VARCHAR(32),
    user_id         VARCHAR(32),
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_column_policy IS '列权限策略表';

-- 1.10 表权限表
CREATE TABLE sys_table_permission (
    id              VARCHAR(32) PRIMARY KEY,
    datasource_id   VARCHAR(32),
    database_name   VARCHAR(200),
    table_name      VARCHAR(200),
    permission_type VARCHAR(50) NOT NULL,      -- SELECT / INSERT / UPDATE / DELETE / ALL
    role_id         VARCHAR(32),
    user_id         VARCHAR(32),
    grant_type      VARCHAR(50) DEFAULT 'ROLE',-- ROLE / USER
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE sys_table_permission IS '表权限表';

-- ============================================================
-- 2. 业务表 - 审批流程
-- ============================================================

-- 2.1 权限申请表
CREATE TABLE app_apply (
    id              VARCHAR(32) PRIMARY KEY,
    applicant_id    VARCHAR(32) NOT NULL,
    apply_type      VARCHAR(50) NOT NULL,      -- TABLE / REPORT / DATASOURCE / MENU
    target_id       VARCHAR(32),
    target_name     VARCHAR(200),
    reason          VARCHAR(1000),
    status          TINYINT DEFAULT 0,         -- 0:待审批 1:通过 2:驳回 3:已撤销
    current_approver VARCHAR(32),
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE app_apply IS '权限申请表';

-- 2.2 审批记录表
CREATE TABLE app_approval_record (
    id              VARCHAR(32) PRIMARY KEY,
    apply_id        VARCHAR(32) NOT NULL,
    approver_id     VARCHAR(32) NOT NULL,
    action          TINYINT NOT NULL,          -- 1:通过 2:驳回 3:转交
    comment         VARCHAR(1000),
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE app_approval_record IS '审批记录表';

-- 2.3 审批流程定义表
CREATE TABLE app_approval_flow (
    id              VARCHAR(32) PRIMARY KEY,
    flow_name       VARCHAR(200) NOT NULL,
    apply_type      VARCHAR(50) NOT NULL,
    step_order      INT DEFAULT 1,
    approver_role   VARCHAR(100),
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE app_approval_flow IS '审批流程定义表';

-- ============================================================
-- 3. 元数据表
-- ============================================================

-- 3.1 数据源配置表
CREATE TABLE meta_datasource (
    id              VARCHAR(32) PRIMARY KEY,
    ds_name         VARCHAR(200) NOT NULL,
    ds_type         VARCHAR(50) NOT NULL,      -- HIVE / DORIS / TRANSWARP / CLICKHOUSE / MYSQL / DM8
    host            VARCHAR(200),
    port            INT,
    database_name   VARCHAR(200),
    username        VARCHAR(200),
    password        VARCHAR(500),              -- 加密存储
    properties      TEXT,                      -- 扩展属性（JSON）
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE meta_datasource IS '数据源配置表';

-- 3.2 数据库信息表
CREATE TABLE meta_database (
    id              VARCHAR(32) PRIMARY KEY,
    datasource_id   VARCHAR(32) NOT NULL,
    database_name   VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    sync_time       DATETIME,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE(datasource_id, database_name)
);

COMMENT ON TABLE meta_database IS '数据库信息表';

-- 3.3 表信息表
CREATE TABLE meta_table (
    id              VARCHAR(32) PRIMARY KEY,
    database_id     VARCHAR(32) NOT NULL,
    table_name      VARCHAR(200) NOT NULL,
    table_type      VARCHAR(50) DEFAULT 'TABLE', -- TABLE / VIEW
    description     VARCHAR(500),
    row_count       BIGINT DEFAULT 0,
    table_size      BIGINT DEFAULT 0,
    sync_time       DATETIME,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE(database_id, table_name)
);

COMMENT ON TABLE meta_table IS '表信息表';

-- 3.4 字段信息表
CREATE TABLE meta_column (
    id              VARCHAR(32) PRIMARY KEY,
    table_id        VARCHAR(32) NOT NULL,
    column_name     VARCHAR(200) NOT NULL,
    column_type     VARCHAR(100),
    column_length   INT,
    column_scale    INT,
    is_nullable     TINYINT DEFAULT 1,
    is_primary_key  TINYINT DEFAULT 0,
    default_value   VARCHAR(500),
    description     VARCHAR(500),
    sort_order      INT DEFAULT 0,
    sensitive_level TINYINT DEFAULT 0,         -- 0:普通 1:敏感 2:高度敏感
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE(table_id, column_name)
);

COMMENT ON TABLE meta_column IS '字段信息表';

-- 3.5 表血缘关系表
CREATE TABLE meta_table_lineage (
    id              VARCHAR(32) PRIMARY KEY,
    source_table_id VARCHAR(32) NOT NULL,
    target_table_id VARCHAR(32) NOT NULL,
    source_column   VARCHAR(200),
    target_column   VARCHAR(200),
    relation_type   VARCHAR(50) DEFAULT 'ETL',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE meta_table_lineage IS '表血缘关系表';

-- ============================================================
-- 4. 集市表
-- ============================================================

-- 4.1 模型定义表
CREATE TABLE mart_model (
    id              VARCHAR(32) PRIMARY KEY,
    model_name      VARCHAR(200) NOT NULL,
    model_code      VARCHAR(100) NOT NULL UNIQUE,
    model_type      VARCHAR(50) DEFAULT 'STAR', -- STAR / SNOWFLAKE
    datasource_id   VARCHAR(32),
    fact_table      VARCHAR(200),
    description     VARCHAR(500),
    status          TINYINT DEFAULT 0,         -- 0:草稿 1:已发布 2:下线
    version         INT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE mart_model IS '模型定义表';

-- 4.2 指标定义表
CREATE TABLE mart_metric (
    id              VARCHAR(32) PRIMARY KEY,
    metric_name     VARCHAR(200) NOT NULL,
    metric_code     VARCHAR(100) NOT NULL UNIQUE,
    metric_type     VARCHAR(50) DEFAULT 'ATOMIC', -- ATOMIC / DERIVED
    expression      VARCHAR(1000),
    data_type       VARCHAR(50) DEFAULT 'BIGINT',
    unit            VARCHAR(50),
    category_id     VARCHAR(32),
    model_id        VARCHAR(32),
    description     VARCHAR(500),
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE mart_metric IS '指标定义表';

-- 4.3 维度定义表
CREATE TABLE mart_dimension (
    id              VARCHAR(32) PRIMARY KEY,
    dim_name        VARCHAR(200) NOT NULL,
    dim_code        VARCHAR(100) NOT NULL UNIQUE,
    dim_type        VARCHAR(50) DEFAULT 'COMMON', -- COMMON / TIME / ORG
    model_id        VARCHAR(32),
    source_table    VARCHAR(200),
    source_column   VARCHAR(200),
    description     VARCHAR(500),
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE mart_dimension IS '维度定义表';

-- 4.4 维度层级表
CREATE TABLE mart_dim_level (
    id              VARCHAR(32) PRIMARY KEY,
    dim_id          VARCHAR(32) NOT NULL,
    level_name      VARCHAR(200),
    level_column    VARCHAR(200),
    level_order     INT DEFAULT 0,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE mart_dim_level IS '维度层级表';

-- 4.5 模型关联关系表
CREATE TABLE mart_model_rel (
    id              VARCHAR(32) PRIMARY KEY,
    model_id        VARCHAR(32) NOT NULL,
    fact_table      VARCHAR(200) NOT NULL,
    dim_table       VARCHAR(200) NOT NULL,
    join_key        VARCHAR(200) NOT NULL,
    join_type       VARCHAR(50) DEFAULT 'INNER', -- INNER / LEFT / RIGHT
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE mart_model_rel IS '模型关联关系表';

-- ============================================================
-- 5. 调度表
-- ============================================================

-- 5.1 任务定义表
CREATE TABLE schedule_job (
    id              VARCHAR(32) PRIMARY KEY,
    job_name        VARCHAR(200) NOT NULL,
    job_type        VARCHAR(50) NOT NULL,       -- SYNC / SQL / REFRESH / PUSH
    job_group       VARCHAR(100),
    cron_expression VARCHAR(100),
    datasource_id   VARCHAR(32),
    target_sql      TEXT,
    target_table    VARCHAR(200),
    params          TEXT,                       -- 任务参数（JSON）
    status          TINYINT DEFAULT 0,          -- 0:停用 1:启用
    retry_count     INT DEFAULT 0,
    retry_interval  INT DEFAULT 60,             -- 秒
    timeout         INT DEFAULT 3600,           -- 秒
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE schedule_job IS '任务定义表';

-- 5.2 任务执行日志表
CREATE TABLE schedule_job_log (
    id              VARCHAR(32) PRIMARY KEY,
    job_id          VARCHAR(32) NOT NULL,
    status          TINYINT,                    -- 0:运行中 1:成功 2:失败
    start_time      DATETIME,
    end_time        DATETIME,
    duration_ms     BIGINT,
    error_message   TEXT,
    result_rows     INT DEFAULT 0,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE schedule_job_log IS '任务执行日志表';

-- 5.3 任务依赖关系表
CREATE TABLE schedule_job_dep (
    id              VARCHAR(32) PRIMARY KEY,
    job_id          VARCHAR(32) NOT NULL,
    dep_job_id      VARCHAR(32) NOT NULL,
    dep_type        VARCHAR(50) DEFAULT 'HARD', -- HARD / SOFT
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE(job_id, dep_job_id)
);

COMMENT ON TABLE schedule_job_dep IS '任务依赖关系表';

-- ============================================================
-- 6. 通知表
-- ============================================================

-- 6.1 消息模板表
CREATE TABLE notify_template (
    id              VARCHAR(32) PRIMARY KEY,
    template_code   VARCHAR(100) NOT NULL UNIQUE,
    template_name   VARCHAR(200) NOT NULL,
    channel         VARCHAR(50) NOT NULL,       -- MAIL / SITE / WECHAT / SMS
    title_template  VARCHAR(500),
    content_template TEXT,
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE notify_template IS '消息模板表';

-- 6.2 推送记录表
CREATE TABLE notify_record (
    id              VARCHAR(32) PRIMARY KEY,
    template_id     VARCHAR(32),
    receiver_id     VARCHAR(32),
    receiver_type   VARCHAR(50) DEFAULT 'USER', -- USER / ROLE / ORG
    channel         VARCHAR(50),
    title           VARCHAR(500),
    content         TEXT,
    status          TINYINT DEFAULT 0,          -- 0:待发送 1:已发送 2:发送失败
    send_time       DATETIME,
    error_message   VARCHAR(500),
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE notify_record IS '推送记录表';

-- 6.3 用户订阅表
CREATE TABLE notify_subscription (
    id              VARCHAR(32) PRIMARY KEY,
    user_id         VARCHAR(32) NOT NULL,
    subscribe_type  VARCHAR(50) NOT NULL,       -- REPORT / METRIC / JOB
    target_id       VARCHAR(32),
    channel         VARCHAR(50) DEFAULT 'SITE',
    status          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE(user_id, subscribe_type, target_id, channel)
);

COMMENT ON TABLE notify_subscription IS '用户订阅表';

-- ============================================================
-- 7. 查询历史表
-- ============================================================

-- 7.1 查询历史表
CREATE TABLE query_history (
    id             VARCHAR(32) PRIMARY KEY,
    user_id        VARCHAR(32),
    datasource_id  VARCHAR(32),
    database_name  VARCHAR(200),
    sql_text       TEXT,
    status         TINYINT DEFAULT 1,          -- 1:成功 0:失败
    cost_ms        BIGINT,
    row_count      INT DEFAULT 0,
    error_message  VARCHAR(1000),
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE query_history IS '查询历史表';

-- ============================================================
-- 8. 可视化表
-- ============================================================

-- 8.1 仪表板表
CREATE TABLE visual_dashboard (
    id               VARCHAR(32) PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(500),
    layout           TEXT,                       -- 布局配置（JSON）
    refresh_interval INT DEFAULT 60,
    status           TINYINT DEFAULT 1,
    create_by        VARCHAR(32),
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE visual_dashboard IS '仪表板表';

-- 8.2 仪表板组件表
CREATE TABLE visual_dashboard_item (
    id            VARCHAR(32) PRIMARY KEY,
    dashboard_id  VARCHAR(32) NOT NULL,
    board_id      VARCHAR(32),                  -- 所属分析板ID
    title         VARCHAR(200),
    chart_type    VARCHAR(50) DEFAULT 'TABLE',  -- BAR / LINE / PIE / TABLE / NUMBER
    datasource_id VARCHAR(32),
    query_sql     TEXT,
    config        TEXT,                         -- 组件配置（JSON）
    pos_x         INT DEFAULT 0,
    pos_y         INT DEFAULT 0,
    width         INT DEFAULT 4,
    height        INT DEFAULT 4,
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE visual_dashboard_item IS '仪表板组件表';

-- 8.3 分析板表（仪表板内嵌套子业务模块）
CREATE TABLE visual_board (
    id               VARCHAR(32) PRIMARY KEY,
    dashboard_id     VARCHAR(32) NOT NULL,
    board_name       VARCHAR(200) NOT NULL,
    subtitle         VARCHAR(500),
    icon             VARCHAR(100),
    board_type       VARCHAR(50) DEFAULT 'ANALYSIS', -- ANALYSIS / CUSTOM
    layout           TEXT,                          -- 板块样式与布局（JSON）
    refresh_interval INT DEFAULT 60,
    collapse         TINYINT DEFAULT 0,             -- 0:展开 1:折叠
    locked           TINYINT DEFAULT 0,             -- 布局锁定
    sort_order       INT DEFAULT 0,
    status           TINYINT DEFAULT 1,
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP()
);

COMMENT ON TABLE visual_board IS '分析板表（仪表板内嵌套子业务模块）';
CREATE INDEX idx_board_dashboard ON visual_board(dashboard_id);

ALTER TABLE visual_dashboard_version ADD COLUMN boards_json LONGTEXT COMMENT '分析板快照(JSON数组)';

-- 8.4 即席查询收藏表
CREATE TABLE visual_adhoc_query (
    id            VARCHAR(32) PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    datasource_id VARCHAR(32),
    sql_text      TEXT,                          -- 查询SQL（支持 ${param} 占位符）
    params_json   TEXT,                          -- 默认参数（JSON）
    folder        VARCHAR(100),                 -- 分组/文件夹
    create_by     VARCHAR(32),
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP(),
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP()
);
COMMENT ON TABLE visual_adhoc_query IS '即席查询收藏表';
CREATE INDEX idx_ahq_user ON visual_adhoc_query(create_by);

-- 8.5 即席查询执行历史表
CREATE TABLE visual_adhoc_history (
    id            VARCHAR(32) PRIMARY KEY,
    adhoc_id      VARCHAR(32),                  -- 关联收藏查询ID
    user_id       VARCHAR(32),                  -- 执行用户ID
    datasource_id VARCHAR(32),
    sql_text      TEXT,                          -- 实际执行SQL
    status        TINYINT DEFAULT 1,            -- 1:成功 0:失败
    cost_ms       BIGINT DEFAULT 0,
    row_count     INT DEFAULT 0,
    error_message VARCHAR(1000),
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP()
);
COMMENT ON TABLE visual_adhoc_history IS '即席查询执行历史表';
CREATE INDEX idx_ahh_user ON visual_adhoc_history(user_id);
CREATE INDEX idx_ahh_adhoc ON visual_adhoc_history(adhoc_id);

-- ============================================================
-- 9. 初始数据
-- ============================================================

-- 7.1 默认管理员
INSERT INTO sys_user (id, username, password, real_name, status) VALUES
('1', 'admin', '$2b$10$hdjujcTK4Qq.n14B3Tfgi.8Kci/7O2aGGtc4.ope8TWyYq6/GNEqO', '系统管理员', 1);

-- 7.2 默认角色
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
('1', 'SUPER_ADMIN', '超级管理员', '拥有所有权限'),
('2', 'ADMIN', '管理员', '系统管理权限'),
('3', 'USER', '普通用户', '基本数据访问权限');

-- 7.3 默认管理员角色
INSERT INTO sys_user_role (id, user_id, role_id) VALUES
('1', '1', '1');

-- 7.4 默认菜单 - 系统管理
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, type, sort_order) VALUES
('1', NULL, '系统管理', '/system', 'Layout', NULL, 0, 1),
('11', '1', '用户管理', '/system/user', 'system/user/index', 'system:user:list', 1, 1),
('12', '1', '角色管理', '/system/role', 'system/role/index', 'system:role:list', 1, 2),
('13', '1', '菜单管理', '/system/menu', 'system/menu/index', 'system:menu:list', 1, 3),
('14', '1', '组织管理', '/system/org', 'system/org/index', 'system:org:list', 1, 4);

-- 7.5 默认菜单 - 数据源
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, type, sort_order) VALUES
('2', NULL, '数据管理', '/data', 'Layout', NULL, 0, 2),
('21', '2', '数据源管理', '/data/datasource', 'data/datasource/index', 'data:datasource:list', 1, 1),
('22', '2', '元数据管理', '/data/metadata', 'data/metadata/index', 'data:metadata:list', 1, 2),
('23', '2', '数据集市', '/data/mart', 'data/mart/index', 'data:mart:list', 1, 3);

-- 7.6 默认菜单 - 查询分析
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, type, sort_order) VALUES
('3', NULL, '查询分析', '/query', 'Layout', NULL, 0, 3),
('31', '3', 'SQL查询', '/query/sql', 'query/sql/index', 'query:sql:execute', 1, 1),
('32', '3', '仪表板', '/query/dashboard', 'query/dashboard/index', 'query:dashboard:view', 1, 2),
('33', '3', '分析板', '/query/analysis', 'query/analysis/index', 'query:analysis:view', 1, 3),
('34', '3', '即席分析', '/visual/adhoc', 'visual/adhoc/index', 'visual:adhoc:execute', 'Magic', 1, 4);

-- 7.7 默认菜单 - 调度与通知
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, type, sort_order) VALUES
('4', NULL, '运维管理', '/ops', 'Layout', NULL, 0, 4),
('41', '4', '任务调度', '/ops/schedule', 'ops/schedule/index', 'ops:schedule:list', 1, 1),
('42', '4', '消息通知', '/ops/notification', 'ops/notification/index', 'ops:notification:list', 1, 2),
('43', '4', '审批管理', '/ops/approval', 'ops/approval/index', 'ops:approval:list', 1, 3);

-- 7.8 默认权限审批流
INSERT INTO app_approval_flow (id, flow_name, apply_type, step_order, approver_role) VALUES
('1', '表权限审批', 'TABLE', 1, 'ADMIN'),
('2', '报表权限审批', 'REPORT', 1, 'ADMIN');

COMMIT;