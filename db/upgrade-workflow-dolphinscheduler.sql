-- ============================================================
-- 存量 MySQL 卷升级：DolphinScheduler 试点绑定与恢复审计
--
-- 用法（在已有数据的库上，通过 mysql 客户端执行）：
--   mysql -uroot -p data_khaos < db/upgrade-workflow-dolphinscheduler.sql
--
-- 新建空数据卷由 docker-compose 自动执行
-- 41-workflow-dolphinscheduler.sql，不需要再执行本脚本。
-- 本脚本只补缺项，可重复执行。
-- ============================================================

USE data_khaos;

DROP PROCEDURE IF EXISTS dk_add_ds_ref_context;
DELIMITER $$
CREATE PROCEDURE dk_add_ds_ref_context()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'workflow_node_run'
          AND COLUMN_NAME = 'ref_context'
    ) THEN
        ALTER TABLE workflow_node_run
            ADD COLUMN ref_context TEXT NULL COMMENT '外部实例运行上下文(JSON)：项目/定义/版本/节点归属' AFTER ref_id;
    END IF;
END$$
DELIMITER ;
CALL dk_add_ds_ref_context();
DROP PROCEDURE IF EXISTS dk_add_ds_ref_context;

CREATE TABLE IF NOT EXISTS workflow_external_recovery (
    id                    VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '主键ID',
    run_id                VARCHAR(32)  NOT NULL COMMENT 'Data Khaos 运行ID',
    node_run_id           VARCHAR(32)  NOT NULL COMMENT 'Data Khaos 节点运行ID',
    wf_id                 VARCHAR(32)  NOT NULL COMMENT 'Data Khaos 工作流ID',
    project_id            VARCHAR(32)  NULL COMMENT 'Data Khaos 项目组ID',
    runtime               VARCHAR(32)  NOT NULL DEFAULT 'DOLPHINSCHEDULER' COMMENT '外部运行时类型',
    remote_project_code   BIGINT       NOT NULL COMMENT '远端项目编码',
    remote_instance_id    BIGINT       NOT NULL COMMENT '远端实例ID',
    action                VARCHAR(40)  NOT NULL DEFAULT 'START_FAILURE_TASK_PROCESS' COMMENT '恢复动作',
    status                VARCHAR(24)  NOT NULL COMMENT 'SUBMITTING/ACCEPTED/POLLING/SUCCEEDED/FAILED/UNKNOWN',
    operator_id           VARCHAR(64)  NULL COMMENT '操作人ID',
    remote_state_before   VARCHAR(40)  NULL COMMENT '恢复前远端状态',
    remote_state_after    VARCHAR(40)  NULL COMMENT '恢复后远端状态',
    remote_run_times      INT          NULL COMMENT '远端实例运行次数',
    local_resume_status   VARCHAR(32)  NULL COMMENT 'RESUMED/COMPLETED/RESUME_FAILED/RESUMING',
    message               VARCHAR(1000) NULL COMMENT '结果说明',
    poll_count            INT          NOT NULL DEFAULT 0 COMMENT '状态轮询次数',
    last_checked_at       DATETIME     NULL COMMENT '最近一次远端状态核查时间',
    create_time           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_external_recovery_node (node_run_id, action),
    KEY idx_external_recovery_status (status, update_time),
    KEY idx_external_recovery_run (run_id)
) ENGINE=InnoDB COMMENT='外部运行时恢复操作审计与状态';
