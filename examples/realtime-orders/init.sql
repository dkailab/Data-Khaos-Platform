CREATE DATABASE orders_source;
CREATE DATABASE orders_serving;
CREATE USER 'cdc_reader'@'%' IDENTIFIED BY 'local-orders-cdc';
GRANT SELECT ON orders_source.* TO 'cdc_reader'@'%';
GRANT RELOAD, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc_reader'@'%';
CREATE USER 'ads_writer'@'%' IDENTIFIED BY 'local-orders-ads';
GRANT SELECT, INSERT, UPDATE, DELETE ON orders_serving.* TO 'ads_writer'@'%';

CREATE TABLE orders_source.orders (
  order_id BIGINT NOT NULL PRIMARY KEY,
  region VARCHAR(32) NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  paid_epoch_ms BIGINT NOT NULL,
  change_version BIGINT NOT NULL,
  CHECK (amount_cents >= 0),
  CHECK (change_version > 0),
  CHECK (status IN ('PAID', 'CANCELLED', 'PENDING'))
) ENGINE=InnoDB;

-- 00:01 and 00:02 Asia/Shanghai on 2026-11-17.
INSERT INTO orders_source.orders VALUES
  (101,'east',10000,'PAID',UNIX_TIMESTAMP('2026-11-16 16:01:02')*1000,1),
  (102,'east', 6500,'PAID',UNIX_TIMESTAMP('2026-11-16 16:01:25')*1000,1),
  (103,'west',12500,'PAID',UNIX_TIMESTAMP('2026-11-16 16:02:02')*1000,1);

CREATE TABLE orders_serving.dwd_paid_orders (
  order_id BIGINT NOT NULL PRIMARY KEY,
  biz_date DATE NOT NULL,
  minute_start DATETIME(3) NOT NULL,
  region VARCHAR(32) NOT NULL,
  amount_cents BIGINT NOT NULL,
  change_version BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE orders_serving.dws_region_minute (
  biz_date DATE NOT NULL,
  minute_start DATETIME(3) NOT NULL,
  region VARCHAR(32) NOT NULL,
  paid_order_count BIGINT NOT NULL,
  gmv_cents DECIMAL(38,0) NOT NULL,
  PRIMARY KEY (biz_date, minute_start, region)
) ENGINE=InnoDB;

-- observed_at 是结果的观测时间：由实时作业在写入该行时以契约时区（Asia/Shanghai）落库，
-- 不是查询时取 now()，因此可用 SELECT MAX(observed_at) 独立复核。
CREATE TABLE orders_serving.ads_region_day (
  biz_date DATE NOT NULL,
  region VARCHAR(32) NOT NULL,
  paid_order_count BIGINT NOT NULL,
  gmv_cents DECIMAL(38,0) NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (biz_date, region)
) ENGINE=InnoDB;

-- 结果级推进：按业务日记录 ADS 最近一次推进时刻与规模，可与 ads_region_day 行数交叉校验。
CREATE TABLE orders_serving.ads_region_day_progress (
  biz_date DATE NOT NULL PRIMARY KEY,
  advanced_at DATETIME(3) NOT NULL,
  region_count BIGINT NOT NULL,
  paid_order_count BIGINT NOT NULL,
  gmv_cents DECIMAL(38,0) NOT NULL
) ENGINE=InnoDB;

-- 作业级推进：按业务日记录作业已消费的 CDC 事件，独立于「是否成交」。
-- 用于在 ADS 无行时区分「该业务日确实无成交」与「作业尚未推进到该业务日」。
CREATE TABLE orders_serving.ads_region_day_events (
  biz_date DATE NOT NULL PRIMARY KEY,
  advanced_at DATETIME(3) NOT NULL,
  event_count BIGINT NOT NULL,
  max_event_time DATETIME(3)
) ENGINE=InnoDB;

-- Batch reconciliation uses a fixed snapshot and its own serving table.
CREATE TABLE orders_serving.batch_order_snapshot LIKE orders_source.orders;
-- 批 ADS 沿用实时表结构（含 observed_at），使两模式各自携带时效证据。
CREATE TABLE orders_serving.ads_region_day_batch LIKE orders_serving.ads_region_day;
