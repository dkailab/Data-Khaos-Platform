-- ============================================================
-- PaaS 第一阶段（PR 1）迁移：编排真实同步任务
-- 目标：调度 → 工作流 → 管道 的关联 ID 可追溯
-- 迁移方式: mysql -uroot -p data_khaos < db/migrate-paas-pr1.sql
-- 幂等性：MySQL 8 下 ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报列已存在，属预期。
-- ============================================================

USE data_khaos;

-- 1) 调度任务：关联工作流/管道
ALTER TABLE schedule_job
    ADD COLUMN ref_type VARCHAR(32) NULL COMMENT '关联对象类型 WORKFLOW/PIPELINE（空时回退 params）' AFTER params,
    ADD COLUMN ref_id   VARCHAR(32) NULL COMMENT '关联对象ID（工作流ID/管道任务ID）' AFTER ref_type;

-- 2) 工作流运行：关联调度
ALTER TABLE workflow_run
    ADD COLUMN schedule_job_id VARCHAR(32) NULL COMMENT '关联调度任务ID（SCHEDULE 触发写入）' AFTER error_message,
    ADD COLUMN schedule_log_id VARCHAR(32) NULL COMMENT '关联调度执行日志ID' AFTER schedule_job_id,
    ADD KEY idx_wf_run_sched (schedule_job_id);

-- 3) 工作流节点运行：关联外部实例（PIPELINE）
ALTER TABLE workflow_node_run
    ADD COLUMN ref_type VARCHAR(32) NULL COMMENT '关联外部实例类型，如 PIPELINE' AFTER error_message,
    ADD COLUMN ref_id   VARCHAR(32) NULL COMMENT '关联外部实例ID，如 pipeline_instance.id' AFTER ref_type,
    ADD KEY idx_wf_node_ref (ref_type, ref_id);

-- 4) 管道实例：关联工作流
ALTER TABLE pipeline_instance
    ADD COLUMN workflow_run_id      VARCHAR(32) NULL COMMENT '关联工作流运行ID（WORKFLOW 触发写入）' AFTER worker,
    ADD COLUMN workflow_node_run_id VARCHAR(32) NULL COMMENT '关联工作流节点运行ID' AFTER workflow_run_id,
    ADD KEY idx_pi_wf_node (workflow_run_id, workflow_node_run_id);
