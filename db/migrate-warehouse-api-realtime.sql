-- ============================================================
-- PR D · 实时 ADS 契约与对账
--
-- 1) warehouse_api_version 增加数据模式：BATCH 走既有分区发布证据，REALTIME 走实时 ADS。
-- 2) 实时契约把「指标单位」「业务时区」「时效阈值」固化在版本上，调用方无需猜测。
-- 3) 存量版本一律回落 BATCH，行为与改造前一致。
--
-- 执行顺序：migrate-warehouse-api-versions.sql
--           -> migrate-warehouse-api-credentials.sql
--           -> migrate-warehouse-api-apps.sql
--           -> migrate-warehouse-api-realtime.sql（本脚本）
-- 与既有迁移一致：每条 ALTER 只执行一次。
-- ============================================================

USE data_khaos;

-- 数据模式：BATCH=离线分区发布（默认，存量行为不变）；REALTIME=实时 ADS
ALTER TABLE warehouse_api_version ADD COLUMN data_mode VARCHAR(16) NOT NULL DEFAULT 'BATCH';

-- 实时 ADS 资产（REALTIME 必填；沿用 metadata 资产解析目标库表）
ALTER TABLE warehouse_api_version ADD COLUMN realtime_asset_id VARCHAR(64) NULL;

-- 对账基线：批 ADS 资产（可选；为空时对账接口明确提示未配置）
ALTER TABLE warehouse_api_version ADD COLUMN realtime_batch_asset_id VARCHAR(64) NULL;

-- 契约时区：observed_at 按此时区解释，响应里原样回显，避免调用方二次猜测
ALTER TABLE warehouse_api_version ADD COLUMN observed_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';

-- 金额单位：实时表以分存储，契约固定按元返回两位小数
ALTER TABLE warehouse_api_version ADD COLUMN amount_unit VARCHAR(8) NOT NULL DEFAULT 'CNY';

-- 时效阈值：lag<=fresh 为 FRESH；lag<=stale 为 LAGGING；超过为 STALE
ALTER TABLE warehouse_api_version ADD COLUMN freshness_fresh_seconds INT NOT NULL DEFAULT 60;
ALTER TABLE warehouse_api_version ADD COLUMN freshness_stale_seconds INT NOT NULL DEFAULT 300;

-- 分区静默窗口（秒）：作业推进距今不足该值时结果判为 IN_PROGRESS，避免半成品被当作 PRODUCED。
-- 默认 0 表示关闭，存量版本行为完全不变；仅 REALTIME 版本可配置为大于 0（上限 3600）。
ALTER TABLE warehouse_api_version ADD COLUMN quiet_seconds INT NOT NULL DEFAULT 0;

-- 批流对账容差（0~1）：NULL/0 表示 STRICT 逐地区精确匹配；如 0.05 表示金额与订单数相对偏差不超过 5% 判 WITHIN_TOLERANCE。
-- 仅 REALTIME 版本可配置；任一侧截断或未定稿时两种模式都不判一致。
ALTER TABLE warehouse_api_version ADD COLUMN reconcile_tolerance DECIMAL(5,4) NULL DEFAULT NULL;

-- ------------------------------------------------------------------
-- 实时契约通用化：列映射与推进信号表
--
-- 全部为 NULL 时按默认「地区成交额」口径解释，存量版本行为完全不变：
--   key=region / value=gmv_cents / count=paid_order_count / observed=observed_at
--   金额除以 realtime_value_divisor 换算为元；推进表默认为 <结果表>_events。
-- 配置后同一响应契约可服务非地区成交额的实时 ADS，无需改 Java 代码。
-- ------------------------------------------------------------------
ALTER TABLE warehouse_api_version ADD COLUMN realtime_key_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_value_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_count_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_observed_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_date_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_value_divisor INT NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_progress_table VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_progress_date_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_progress_advanced_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_progress_count_column VARCHAR(64) NULL;
ALTER TABLE warehouse_api_version ADD COLUMN realtime_progress_event_time_column VARCHAR(64) NULL;

-- 调用审计补时效快照：事后可复核「当时返回的是哪种时效」
ALTER TABLE warehouse_api_call_audit ADD COLUMN freshness VARCHAR(16) NULL;

-- 回滚（人工确认后执行，默认不执行）
-- ALTER TABLE warehouse_api_version
--     DROP COLUMN data_mode, DROP COLUMN realtime_asset_id, DROP COLUMN realtime_batch_asset_id,
--     DROP COLUMN observed_timezone, DROP COLUMN amount_unit,
--     DROP COLUMN freshness_fresh_seconds, DROP COLUMN freshness_stale_seconds, DROP COLUMN quiet_seconds,
--     DROP COLUMN reconcile_tolerance,
--     DROP COLUMN realtime_key_column, DROP COLUMN realtime_value_column, DROP COLUMN realtime_count_column,
--     DROP COLUMN realtime_observed_column, DROP COLUMN realtime_date_column, DROP COLUMN realtime_value_divisor,
--     DROP COLUMN realtime_progress_table, DROP COLUMN realtime_progress_date_column,
--     DROP COLUMN realtime_progress_advanced_column,
--     DROP COLUMN realtime_progress_count_column, DROP COLUMN realtime_progress_event_time_column;
-- ALTER TABLE warehouse_api_call_audit DROP COLUMN freshness;
