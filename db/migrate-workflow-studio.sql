-- ============================================================
-- 数据开发工作台（调度编排）增强迁移（在 data_khaos 库执行）
-- 迁移方式: source db/migrate-workflow-studio.sql
--
-- 覆盖：
--   1. 节点级任务配置：描述、跳过、执行器组、独立调度、前置条件、节点参数
--   2. 工作流发布信息：发布版本号、发布时间
--   3. SQL 文件资源（草稿/发布两态，供任务节点引用）
--   4. 跨任务流上游依赖（含依赖策略与参数映射）
--
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS，本脚本每个 ALTER 只执行一次；
--       重复执行会因「列已存在」报错，可忽略已存在的报错或先核对列清单。
-- ============================================================

USE data_khaos;

-- 1. 节点任务配置
ALTER TABLE workflow_node ADD COLUMN description VARCHAR(500) NULL COMMENT '节点描述';
ALTER TABLE workflow_node ADD COLUMN skip_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否跳过执行 0:否 1:是（跳过仍释放下游）';
ALTER TABLE workflow_node ADD COLUMN executor_group VARCHAR(64) NULL COMMENT '执行器组标识';
ALTER TABLE workflow_node ADD COLUMN independent_schedule TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许独立调度 0:否 1:是';
ALTER TABLE workflow_node ADD COLUMN precondition VARCHAR(1000) NULL COMMENT '前置条件表达式，空表示始终执行';
ALTER TABLE workflow_node ADD COLUMN node_params TEXT NULL COMMENT '节点级参数(JSON)，覆盖工作流参数';

-- 2. 工作流发布信息
ALTER TABLE workflow_def ADD COLUMN published_revision INT NULL COMMENT '最近发布绑定的版本号';
ALTER TABLE workflow_def ADD COLUMN published_at DATETIME NULL COMMENT '最近发布时间';

-- 3. SQL 文件资源：草稿内容与已发布内容分离，外部任务节点可指定读取哪一态
CREATE TABLE IF NOT EXISTS workflow_sql_file (
    id                VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    project_id        VARCHAR(32)  NULL COMMENT '所属项目组ID',
    folder            VARCHAR(255) NOT NULL DEFAULT '/SQL文件' COMMENT '资源目录，以 / 开头',
    name              VARCHAR(200) NOT NULL COMMENT '文件名',
    description       VARCHAR(500) NULL COMMENT '说明',
    datasource_id     VARCHAR(64)  NULL COMMENT '默认数据源ID',
    draft_content     MEDIUMTEXT   NULL COMMENT '草稿内容',
    published_content MEDIUMTEXT   NULL COMMENT '已发布内容快照',
    draft_version     INT          NOT NULL DEFAULT 1 COMMENT '草稿版本号，每次保存递增',
    published_version INT          NOT NULL DEFAULT 0 COMMENT '已发布版本号，0 表示未发布',
    updated_by        VARCHAR(64)  NULL COMMENT '最后修改人ID',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0:正常 1:删除',
    create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_sql_file_project (project_id, folder),
    KEY idx_sql_file_name (name)
) ENGINE=InnoDB COMMENT='工作流 SQL 文件资源表';

-- 4. 跨任务流上游依赖：上游成功后才允许当前任务流运行
CREATE TABLE IF NOT EXISTS workflow_upstream (
    id              VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    wf_id           VARCHAR(32) NOT NULL COMMENT '下游任务流ID',
    upstream_wf_id  VARCHAR(32) NOT NULL COMMENT '上游任务流ID',
    depend_strategy VARCHAR(16) NOT NULL DEFAULT 'STRICT' COMMENT 'STRICT:同业务日成功 LOOSE:最近成功即可 OFF:仅展示',
    param_mapping   TEXT        NULL COMMENT '参数映射(JSON): {"本地参数":"上游参数"}',
    enabled         TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用 0:否 1:是',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0:正常 1:删除',
    create_time     DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_wf_upstream (wf_id, upstream_wf_id),
    KEY idx_upstream_wf (upstream_wf_id)
) ENGINE=InnoDB COMMENT='工作流上游依赖表';

-- 回滚（人工确认后执行，默认不执行）
-- ALTER TABLE workflow_node
--     DROP COLUMN description, DROP COLUMN skip_flag, DROP COLUMN executor_group,
--     DROP COLUMN independent_schedule, DROP COLUMN precondition, DROP COLUMN node_params;
-- ALTER TABLE workflow_def DROP COLUMN published_revision, DROP COLUMN published_at;
-- DROP TABLE IF EXISTS workflow_sql_file;
-- DROP TABLE IF EXISTS workflow_upstream;
