-- ============================================================
-- 管道阶段计数对账（PIPELINE_COUNT）
--
-- workflow 侧的采样器（需开启 FLINK_PIPELINE_COUNT_SAMPLER_ENABLED=true）
-- 每轮为每个运行中算子写一条累计进出记录数：
--   sample_batch 同一轮采样共用，质量侧只取最新一批，避免跨轮次混算；
--   stage_order  作业拓扑顺序（0 为最上游），不依赖采样 SQL 的 ORDER BY。
--
-- 采样 SQL 示例（rule_config.metricsSql）：
--   SELECT stage, records_in, records_out
--   FROM dk_pipeline_stage_count
--   WHERE job_id = '${jobId}'
--     AND sample_batch = (
--       SELECT sample_batch FROM dk_pipeline_stage_count
--       WHERE job_id = '${jobId}' ORDER BY sampled_at DESC, id DESC LIMIT 1)
--   ORDER BY stage_order
-- 注意：sample_batch 是随机 UUID，不能对它取 MAX；必须按 sampled_at 找最新一批。
-- ${jobId} 由规则 rule_config.jobId 注入；不写 job_id 时按全局最新一批对账，
-- 适合单作业环境。采样点缺失时规则报 ERROR，不会默认通过。
-- ============================================================

USE data_khaos;

CREATE TABLE IF NOT EXISTS dk_pipeline_stage_count (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    run_id       VARCHAR(64)  NOT NULL,
    job_id       VARCHAR(64)  NOT NULL,
    sample_batch VARCHAR(36)  NOT NULL,
    vertex_id    VARCHAR(64)  NOT NULL,
    stage        VARCHAR(128) NOT NULL,
    stage_order  INT          NOT NULL DEFAULT 0,
    sampled_at   DATETIME     NOT NULL,
    records_in   BIGINT       NOT NULL DEFAULT 0,
    records_out  BIGINT       NOT NULL DEFAULT 0,
    KEY idx_stage_count_batch (job_id, sample_batch, stage_order),
    KEY idx_stage_count_run (run_id, sampled_at),
    KEY idx_stage_count_time (sampled_at)
) ENGINE=InnoDB COMMENT='Flink 算子阶段计数采样（管道对账用）';
