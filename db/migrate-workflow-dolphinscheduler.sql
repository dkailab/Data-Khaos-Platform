-- ============================================================
-- DolphinScheduler 外部运行时绑定、实例上下文与恢复审计
--
-- 说明：
--   1. workflow_node_run.ref_context 保存当次实际使用的远端映射
--      （项目编码、工作流定义编码/版本、Data Khaos 节点归属）。
--   2. workflow_external_recovery 记录从失败节点恢复的请求、操作人、
--      远端实例、操作结果和本地下游续跑状态，用于并发互斥与审计。
--
-- 本脚本按项目约定只执行一次；重复执行 ADD COLUMN 会报列已存在。
-- ============================================================

USE data_khaos;

ALTER TABLE workflow_node_run
    ADD COLUMN ref_context TEXT NULL COMMENT '外部实例运行上下文(JSON)：项目/定义/版本/节点归属' AFTER ref_id;

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
