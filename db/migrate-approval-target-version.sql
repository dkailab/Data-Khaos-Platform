-- ============================================================
-- 审批绑定目标版本（P0-2）
--
-- 用途：发布/调度前按「目标 + 修订号」查询已通过的审批。
-- 审批通过后再次编辑会推进 revision，旧审批自然失效，必须重新审批。
--
-- 与既有迁移一致：每条 ALTER 只执行一次。
-- ============================================================

USE data_khaos;

-- 目标 ID 可能超过 32 字符（如 Flink 作业资产 ID）
ALTER TABLE app_apply MODIFY COLUMN target_id VARCHAR(64) COMMENT '申请目标ID';

-- 目标修订号；历史数据为 NULL，不参与版本匹配，不会被误当作已审批
ALTER TABLE app_apply ADD COLUMN target_revision INT NULL COMMENT '目标修订号，审批绑定到具体版本' AFTER target_id;

-- 版本审批查询：applyType + targetId + targetRevision + status
CREATE INDEX idx_app_apply_target ON app_apply (apply_type, target_id, target_revision, status);

-- 回滚（人工确认后执行，默认不执行）
-- DROP INDEX idx_app_apply_target ON app_apply;
-- ALTER TABLE app_apply DROP COLUMN target_revision;
