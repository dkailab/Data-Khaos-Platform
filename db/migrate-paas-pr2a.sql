-- ============================================================
-- PaaS 第一阶段（PR 2A）迁移：触发、业务日期与补数
-- 目标：工作流支持按业务日期运行、请求幂等键、同日活跃运行互斥、区间补数
-- 迁移方式: mysql -uroot -p data_khaos < db/migrate-paas-pr2a.sql
-- 幂等性：MySQL 8 下 ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报列已存在，属预期。
--         旧记录的新增字段均为 NULL，保持可用（旧触发无 requestId/bizDate）。
-- ============================================================

USE data_khaos;

-- 1) 工作流定义：所属项目（用于补数/带日期运行的项目范围校验；为空表示不校验，兼容旧数据）
ALTER TABLE workflow_def
    ADD COLUMN project_id VARCHAR(32) NULL COMMENT '所属项目ID（空表示不校验项目范围）' AFTER owner;

-- 2) 工作流运行：业务日期、请求幂等键、计划触发时刻与拒绝信息
ALTER TABLE workflow_run
    ADD COLUMN biz_date       VARCHAR(10)  NULL COMMENT '业务日期 YYYY-MM-DD（旧记录可为空）' AFTER trigger_params,
    ADD COLUMN request_id     VARCHAR(96)  NULL COMMENT '请求幂等键（旧触发为空）' AFTER biz_date,
    ADD COLUMN request_params TEXT         NULL COMMENT '规范化请求参数（供同键异参诊断）' AFTER request_id,
    ADD COLUMN request_hash   VARCHAR(64)  NULL COMMENT '规范化请求参数 SHA-256（同键异参校验）' AFTER request_params,
    ADD COLUMN scheduled_at   DATETIME     NULL COMMENT '计划触发时刻（调度触发写入）' AFTER request_hash,
    ADD COLUMN reject_code    VARCHAR(32)  NULL COMMENT '拒绝码 RUN_ALREADY_ACTIVE/REQUEST_CONFLICT/PROJECT_FORBIDDEN/INVALID_DATE/RANGE_EXCEEDED/FORBIDDEN' AFTER error_message,
    ADD COLUMN reject_message VARCHAR(2000) NULL COMMENT '拒绝原因' AFTER reject_code,
    ADD KEY idx_wf_run_biz (wf_id, biz_date),
    -- MySQL 唯一索引允许多个 NULL：旧触发（request_id 为空）不受约束
    ADD UNIQUE KEY uk_wf_run_request (wf_id, request_id);

-- 3) 调度执行日志：计划触发时刻（cron 边界，区别于实际开始时间）
ALTER TABLE schedule_job_log
    ADD COLUMN scheduled_at DATETIME NULL COMMENT '计划触发时刻（cron 边界，用于生成稳定幂等键）' AFTER start_time;

-- 4) 业务日活跃运行锁：保证同一 (工作流, 业务日) 仅一条活跃运行，运行终态时释放
CREATE TABLE IF NOT EXISTS workflow_bizdate_lock (
    id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    wf_id       VARCHAR(32) NOT NULL COMMENT '工作流ID',
    biz_date    VARCHAR(10) NOT NULL COMMENT '业务日期 YYYY-MM-DD',
    run_id      VARCHAR(32) NOT NULL COMMENT '占用该业务日的运行ID',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_wf_bizdate (wf_id, biz_date),
    KEY idx_lock_run (run_id)
) ENGINE=InnoDB COMMENT='工作流业务日活跃运行锁（终态释放）';
