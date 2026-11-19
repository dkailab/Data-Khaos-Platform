-- ============================================================
-- PaaS 第一阶段（PR 1）种子数据：调度 → 工作流 → 管道 编排闭环
-- 依赖: mysql-init.sql + migrate-workflow.sql + migrate-paas-pr1.sql + seed-project-group.sql
-- 内容:
--   1) 示例源/目标表与两行种子数据（MySQL → MySQL，DB_SYNC 引擎可真实跑通）
--   2) 管道任务 pipe_pr1_orders
--   3) 工作流 wf_pr1_orders（单 PIPELINE 节点）
--   4) 调度任务 job_pr1_wf（WORKFLOW，默认停用，验收时启用）
-- ============================================================

USE data_khaos;

-- 1) 示例表与数据
CREATE TABLE IF NOT EXISTS pr1_src_orders (
    order_id   VARCHAR(32) NOT NULL PRIMARY KEY,
    region     VARCHAR(32),
    amount     DECIMAL(12,2),
    status     VARCHAR(16),
    create_time DATETIME
) ENGINE=InnoDB COMMENT='PR1 示例-订单源表';

CREATE TABLE IF NOT EXISTS pr1_dst_orders (
    order_id   VARCHAR(32) NOT NULL PRIMARY KEY,
    region     VARCHAR(32),
    amount     DECIMAL(12,2),
    status     VARCHAR(16),
    create_time DATETIME
) ENGINE=InnoDB COMMENT='PR1 示例-订单目标表';

INSERT INTO pr1_src_orders (order_id, region, amount, status, create_time) VALUES
('PR1-1001', '华东', 199.00, 'PAID',   '2026-11-18 10:00:00'),
('PR1-1002', '华北',  59.50, 'PAID',   '2026-11-18 10:05:00'),
('PR1-1003', '华南', 320.00, 'CANCEL', '2026-11-18 10:10:00')
ON DUPLICATE KEY UPDATE amount = VALUES(amount);

-- 2) 管道任务（DB_SYNC：MySQL data_khaos.pr1_src_orders → data_khaos.pr1_dst_orders）
INSERT INTO pipeline_task (id, task_name, task_type, engine, source_ds_id, source_table,
                           target_ds_id, target_table, cron_expr, project_group_id, status, create_by)
VALUES ('pipe_pr1_orders', 'PR1-订单同步', 'SYNC', 'DB_SYNC', 'ds_dw', 'pr1_src_orders',
        'ds_app', 'pr1_dst_orders', NULL, NULL, 1, 'system')
ON DUPLICATE KEY UPDATE task_name = VALUES(task_name), status = VALUES(status);

-- 3) 工作流定义 + PIPELINE 节点
INSERT INTO workflow_def (id, name, code, cron_expression, status, description, owner, params,
                          deleted, create_time, update_time)
VALUES ('wf_pr1_orders', 'PR1-订单同步工作流', 'wf_pr1_orders', NULL, 1,
        'PR1 验收工作流：触发订单同步管道', 'system', NULL, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status);

INSERT INTO workflow_node (id, wf_id, node_code, node_name, node_type, config_json,
                           pos_x, pos_y, timeout, retry_count, retry_interval,
                           deleted, create_time, update_time)
VALUES ('wn_pr1_pipe', 'wf_pr1_orders', 'n_pipe', '订单同步', 'PIPELINE',
        '{"pipelineTaskId":"pipe_pr1_orders"}', 120, 120, 300, 0, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE node_name = VALUES(node_name), config_json = VALUES(config_json);

-- 4) 调度任务（WORKFLOW；默认停用，验收时通过 /api/schedule/job/{id}/start 启用）
INSERT INTO schedule_job (id, job_name, job_type, job_group, cron_expression, params,
                          ref_type, ref_id, status, retry_count, retry_interval, timeout,
                          create_time, update_time)
VALUES ('job_pr1_wf', 'PR1-订单同步调度', 'WORKFLOW', 'default', '0 */5 * * * ?', NULL,
        'WORKFLOW', 'wf_pr1_orders', 0, 0, 60, 600, NOW(), NOW())
ON DUPLICATE KEY UPDATE job_name = VALUES(job_name), ref_type = VALUES(ref_type),
  ref_id = VALUES(ref_id), status = VALUES(status);

-- 5) 兼容旧配置：无 ref_id 时回退 params.pipelineTaskId（SYNC 示例，默认停用）
INSERT INTO schedule_job (id, job_name, job_type, job_group, cron_expression, params,
                          ref_type, ref_id, status, retry_count, retry_interval, timeout,
                          create_time, update_time)
VALUES ('job_pr1_sync', 'PR1-订单同步(直连管道)', 'SYNC', 'default', NULL,
        '{"pipelineTaskId":"pipe_pr1_orders"}',
        NULL, NULL, 0, 0, 60, 600, NOW(), NOW())
ON DUPLICATE KEY UPDATE job_name = VALUES(job_name), params = VALUES(params), status = VALUES(status);
