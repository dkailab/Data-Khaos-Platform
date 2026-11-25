-- ============================================================
-- PR D · 实时 ADS 契约验证种子
--
-- 用途：在没有 Flink/Kafka 实验环境时，用它复现实时契约所需的表形态与数据，
--       让「观测时间 + 时效状态 + 业务日推进信号 + 批侧对账」可以在真实服务上验证。
-- 前置：migrate-warehouse-api-realtime.sql 已执行。
--
-- 与 examples/realtime-orders 的关系：字段与语义完全一致，
--       作业侧写入的是 ads_region_day / _progress / _events，这里落在 rt_ 前缀表上，
--       避免与实验环境的库表互相覆盖。
--
-- 可重复执行：按业务日先清后插；资产按主键 upsert。
-- ============================================================

USE data_khaos;

-- 结果表：gmv 以分存储，observed_at 由作业按契约时区（Asia/Shanghai）落库
CREATE TABLE IF NOT EXISTS rt_ads_region_day (
    biz_date DATE NOT NULL,
    region VARCHAR(32) NOT NULL,
    paid_order_count BIGINT NOT NULL,
    gmv_cents DECIMAL(38,0) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (biz_date, region)
) ENGINE=InnoDB;

-- 作业推进信号：按业务日记录已消费事件，独立于「是否成交」
CREATE TABLE IF NOT EXISTS rt_ads_region_day_events (
    biz_date DATE NOT NULL PRIMARY KEY,
    advanced_at DATETIME(3) NOT NULL,
    event_count BIGINT NOT NULL,
    max_event_time DATETIME(3)
) ENGINE=InnoDB;

-- 对账基线：批 ADS，结构与实时一致，各自带观测时间
CREATE TABLE IF NOT EXISTS rt_ads_region_day_batch LIKE rt_ads_region_day;

DELETE FROM rt_ads_region_day WHERE biz_date = '2026-11-17';
DELETE FROM rt_ads_region_day_events WHERE biz_date = '2026-11-17';
DELETE FROM rt_ads_region_day_batch WHERE biz_date = '2026-11-17';

-- 观测时间取当下，使实时契约返回 FRESH；UTC 加 8 小时得到契约时区墙钟
SET @observed = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR);

INSERT INTO rt_ads_region_day VALUES
    ('2026-11-17', 'east', 2, 10000, @observed),
    ('2026-11-17', 'west', 1, 12500, @observed);

-- 3 条已支付订单事件：east 2 笔 100.00，west 1 笔 125.00
INSERT INTO rt_ads_region_day_events VALUES
    ('2026-11-17', @observed, 3, '2026-11-17 00:02:02');

-- 批侧与实时一致，用于验证 matched=true
INSERT INTO rt_ads_region_day_batch VALUES
    ('2026-11-17', 'east', 2, 10000, @observed),
    ('2026-11-17', 'west', 1, 12500, @observed);

-- 资产登记：实时契约通过 metadata 资产解析目标库表
INSERT IGNORE INTO meta_database (id, datasource_id, database_name, description, sync_time, create_time)
VALUES ('md_pr2_dw', 'ds_dw', 'data_khaos', '数仓库（实时与批 ADS）', NULL, NOW());

INSERT INTO meta_table (id, database_id, table_name, table_type, description, row_count, table_size,
                        sync_time, create_time, update_time)
VALUES ('mt_rt_ads_region_day', 'md_pr2_dw', 'rt_ads_region_day', 'TABLE',
        '实时 ADS 地区成交额（PR D 契约验证）', 2, 0, NULL, NOW(), NOW()),
       ('mt_rt_ads_region_day_batch', 'md_pr2_dw', 'rt_ads_region_day_batch', 'TABLE',
        '批 ADS 地区成交额（PR D 对账基线）', 2, 0, NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE table_name = VALUES(table_name), database_id = VALUES(database_id),
                        description = VALUES(description), update_time = NOW();
