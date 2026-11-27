-- ============================================================
-- 调度执行层改造（参考 DolphinScheduler 的领取模型）
--
-- next_fire_time：下次触发时刻，扫描走索引而不是全表算 cron
-- owner / lease_until：原子领取 + 租约，多副本只有一个实例能执行
-- executor_group：执行器组，空表示任意实例可执行
-- priority：同批到期任务按优先级倒序领取
--
-- 与既有迁移一致：每条 ALTER 只执行一次。
-- ============================================================

USE data_khaos;

ALTER TABLE schedule_job ADD COLUMN executor_group VARCHAR(32) NULL COMMENT '执行器组，空=任意实例';
ALTER TABLE schedule_job ADD COLUMN priority INT NOT NULL DEFAULT 0 COMMENT '优先级，越大越先';
ALTER TABLE schedule_job ADD COLUMN next_fire_time DATETIME NULL COMMENT '下次触发时刻';
ALTER TABLE schedule_job ADD COLUMN owner VARCHAR(64) NULL COMMENT '领取实例';
ALTER TABLE schedule_job ADD COLUMN lease_until DATETIME NULL COMMENT '租约到期时间';

CREATE INDEX idx_schedule_job_due ON schedule_job (status, next_fire_time);
CREATE INDEX idx_schedule_job_group_due ON schedule_job (executor_group, status, next_fire_time);

-- 回滚（人工确认后执行，默认不执行）
-- DROP INDEX idx_schedule_job_group_due ON schedule_job;
-- DROP INDEX idx_schedule_job_due ON schedule_job;
-- ALTER TABLE schedule_job
--     DROP COLUMN executor_group, DROP COLUMN priority, DROP COLUMN next_fire_time,
--     DROP COLUMN owner, DROP COLUMN lease_until;
