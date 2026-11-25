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

-- 调用审计补时效快照：事后可复核「当时返回的是哪种时效」
ALTER TABLE warehouse_api_call_audit ADD COLUMN freshness VARCHAR(16) NULL;

-- 回滚（人工确认后执行，默认不执行）
-- ALTER TABLE warehouse_api_version
--     DROP COLUMN data_mode, DROP COLUMN realtime_asset_id, DROP COLUMN realtime_batch_asset_id,
--     DROP COLUMN observed_timezone, DROP COLUMN amount_unit,
--     DROP COLUMN freshness_fresh_seconds, DROP COLUMN freshness_stale_seconds;
-- ALTER TABLE warehouse_api_call_audit DROP COLUMN freshness;
