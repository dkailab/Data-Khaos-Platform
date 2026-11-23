-- ============================================================
-- PaaS PR2C 迁移：质量稽核接入工作流（门禁）+ 结论语义
-- 目标：快照记录来源工作流运行/节点与业务日期，区分 PASS/FAIL/ERROR；
--       规则结果区分「规则不满足（FAIL）」与「规则执行异常（ERROR）」；
--       提供订单链路的示例质量任务与门禁 DAG（dws 质检通过后才发布 ADS）。
-- 迁移方式: mysql -uroot -p data_khaos < db/migrate-paas-pr2c.sql
-- 幂等性：ADD COLUMN 重复执行会报列已存在，属预期；示例数据可重复执行。
-- ============================================================

USE data_khaos;

-- 1) 快照：关联工作流运行与业务日期 + 结论
ALTER TABLE dquality_snapshot
    ADD COLUMN workflow_run_id      VARCHAR(32) NULL COMMENT '来源工作流运行ID（WORKFLOW 触发时写入）' AFTER task_id,
    ADD COLUMN workflow_node_run_id VARCHAR(32) NULL COMMENT '来源工作流节点运行ID' AFTER workflow_run_id,
    ADD COLUMN biz_date             VARCHAR(10) NULL COMMENT '业务日期 YYYY-MM-DD' AFTER workflow_node_run_id,
    ADD COLUMN conclusion           VARCHAR(16) NULL COMMENT '结论 PASS/FAIL/ERROR' AFTER biz_date,
    ADD COLUMN error_code           VARCHAR(32) NULL COMMENT 'ERROR 时的错误码（如 RULE_ERROR/QUALITY_TIMEOUT）' AFTER conclusion,
    ADD KEY idx_dqs_wf_run (workflow_run_id),
    ADD KEY idx_dqs_wf_node (workflow_run_id, workflow_node_run_id);

-- 2) 规则结果：区分规则执行异常与规则不满足
ALTER TABLE dquality_rule_result
    ADD COLUMN error_code VARCHAR(32) NULL COMMENT '规则执行异常码（为空表示规则正常判定，passed=0 即不满足）' AFTER passed;

-- 2.1) 规则：业务日期列（门禁按同一业务日过滤源/目标数据）
ALTER TABLE dquality_rule
    ADD COLUMN date_column VARCHAR(64) NULL COMMENT '业务日期列（提供且传入 bizDate 时按该列过滤）' AFTER column_name;

-- ============================================================
-- 3) 示例质量规则：dws 日地区汇总（ADS 发布前门禁）
-- ============================================================
INSERT INTO dquality_rule
    (id, project_group_id, rule_code, rule_name, rule_type, datasource_id, database_name, table_name,
     column_name, date_column, rule_config, weight, alert_threshold, status, create_by, create_time)
VALUES
 ('dqr_pr2c_dws_nn_date', NULL, 'PR2C-DWS-NN-DATE', 'DWS业务日期非空', 'NOT_NULL',
  'ds_dw', 'data_khaos', 'pr2_dws_region_daily', 'stat_date', 'stat_date', NULL, 1, 0, 1, 'system', NOW()),
 ('dqr_pr2c_dws_nn_region', NULL, 'PR2C-DWS-NN-REGION', 'DWS地区非空', 'NOT_NULL',
  'ds_dw', 'data_khaos', 'pr2_dws_region_daily', 'region', 'stat_date', NULL, 1, 0, 1, 'system', NOW()),
 ('dqr_pr2c_dws_range_amount', NULL, 'PR2C-DWS-RANGE-AMOUNT', 'DWS金额非负', 'VALUE_RANGE',
  'ds_dw', 'data_khaos', 'pr2_dws_region_daily', 'amount_sum', 'stat_date', '{"min":0}', 1, 0, 1, 'system', NOW()),
 ('dqr_pr2c_dws_recon', NULL, 'PR2C-DWS-RECON', 'DWS与源订单金额对账', 'CUSTOM_SQL',
  'ds_dw', 'data_khaos', 'pr2_dws_region_daily', NULL, NULL,
  '{"customSql":"SELECT s.region AS region, s.src_amt AS src_amt, IFNULL(d.amount_sum, 0) AS dws_amt, ABS(s.src_amt - IFNULL(d.amount_sum, 0)) AS diff_amt FROM (SELECT region, SUM(amount) AS src_amt FROM data_khaos.pr2_orders_src WHERE order_date = ''${bizDate}'' AND status = ''PAID'' GROUP BY region) s LEFT JOIN (SELECT region, amount_sum FROM data_khaos.pr2_dws_region_daily WHERE stat_date = ''${bizDate}'') d ON s.region = d.region WHERE ABS(s.src_amt - IFNULL(d.amount_sum, 0)) > 0.01"}',
  2, 0, 1, 'system', NOW())
ON DUPLICATE KEY UPDATE
    rule_name = VALUES(rule_name),
    rule_type = VALUES(rule_type),
    column_name = VALUES(column_name),
    date_column = VALUES(date_column),
    rule_config = VALUES(rule_config),
    alert_threshold = VALUES(alert_threshold),
    status = VALUES(status);

-- ============================================================
-- 4) 示例质量任务：绑定上述规则
-- ============================================================
INSERT INTO dquality_task
    (id, project_group_id, task_name, rule_ids, cron_expr, status, create_by, create_time)
VALUES
 ('dq_pr2c_dws', NULL, 'PR2C-DWS发布门禁', '["dqr_pr2c_dws_nn_date","dqr_pr2c_dws_nn_region","dqr_pr2c_dws_range_amount","dqr_pr2c_dws_recon"]',
  NULL, 1, 'system', NOW())
ON DUPLICATE KEY UPDATE
    task_name = VALUES(task_name),
    rule_ids = VALUES(rule_ids),
    status = VALUES(status);

-- ============================================================
-- 5) 示例门禁 DAG：ODS → DWD → DWS → 质量门禁 → ADS
--    质量节点 gate=BLOCK：结论为 FAIL/ERROR 时节点失败，后继 ADS 被跳过
-- ============================================================
INSERT INTO workflow_def
    (id, name, code, cron_expression, status, description, owner, params, deleted, create_time, update_time)
VALUES
 ('wf_pr2c_orders', 'PR2C-订单链路（质量门禁）', 'wf_pr2c_orders', NULL, 1,
  'ODS→DWD→DWS→质量门禁→ADS：DWS 质量结论非 PASS 时不发布 ADS', 'system', NULL, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    status = VALUES(status),
    description = VALUES(description);

DELETE FROM workflow_edge WHERE wf_id = 'wf_pr2c_orders';
DELETE FROM workflow_node WHERE wf_id = 'wf_pr2c_orders';

INSERT INTO workflow_node
    (id, wf_id, node_code, node_name, node_type, config_json, pos_x, pos_y, timeout, retry_count, retry_interval, deleted, create_time, update_time)
VALUES
 ('wn_pr2c_ods',      'wf_pr2c_orders', 'n_ods',      'ODS 订单同步',   'PIPELINE',
  '{"pipelineTaskId":"pipe_pr2b_ods"}', 80,  160, 0, 0, 0, 0, NOW(), NOW()),
 ('wn_pr2c_dwd',      'wf_pr2c_orders', 'n_dwd',      'DWD 支付订单',   'PIPELINE',
  '{"pipelineTaskId":"pipe_pr2b_dwd"}', 260, 160, 0, 0, 0, 0, NOW(), NOW()),
 ('wn_pr2c_dws',      'wf_pr2c_orders', 'n_dws',      'DWS 日地区汇总', 'PIPELINE',
  '{"pipelineTaskId":"pipe_pr2b_dws"}', 440, 160, 0, 0, 0, 0, NOW(), NOW()),
 ('wn_pr2c_quality',  'wf_pr2c_orders', 'n_quality',  'DWS 质量门禁',   'QUALITY',
  '{"qualityTaskId":"dq_pr2c_dws","gate":"BLOCK"}', 620, 160, 900, 0, 0, 0, NOW(), NOW()),
 ('wn_pr2c_ads',      'wf_pr2c_orders', 'n_ads',      'ADS 地区成交额', 'PIPELINE',
  '{"pipelineTaskId":"pipe_pr2b_ads"}', 800, 160, 0, 0, 0, 0, NOW(), NOW());

INSERT INTO workflow_edge
    (id, wf_id, from_code, to_code, condition_expr, deleted, create_time, update_time)
VALUES
 ('wfe_pr2c_1', 'wf_pr2c_orders', 'n_ods',     'n_dwd',     NULL, 0, NOW(), NOW()),
 ('wfe_pr2c_2', 'wf_pr2c_orders', 'n_dwd',     'n_dws',     NULL, 0, NOW(), NOW()),
 ('wfe_pr2c_3', 'wf_pr2c_orders', 'n_dws',     'n_quality', NULL, 0, NOW(), NOW()),
 ('wfe_pr2c_4', 'wf_pr2c_orders', 'n_quality', 'n_ads',     NULL, 0, NOW(), NOW());
