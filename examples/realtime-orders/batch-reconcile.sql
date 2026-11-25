SET 'pipeline.name' = 'Orders bounded reconciliation';
SET 'execution.runtime-mode' = 'batch';
SET 'parallelism.default' = '1';
SET 'table.local-time-zone' = 'Asia/Shanghai';
SET 'table.dml-sync' = 'true';

CREATE TABLE batch_order_snapshot (
  order_id BIGINT,
  region STRING,
  amount_cents BIGINT,
  status STRING,
  paid_epoch_ms BIGINT,
  change_version BIGINT,
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'batch_order_snapshot',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads'
);

CREATE TEMPORARY VIEW latest_orders AS
SELECT *, 0 AS is_deleted FROM batch_order_snapshot;

-- INCLUDE paid-order-views.sql

CREATE TABLE ads_region_day_batch (
  biz_date DATE,
  region STRING,
  paid_order_count BIGINT,
  gmv_cents DECIMAL(38,0),
  observed_at TIMESTAMP(3),
  PRIMARY KEY (biz_date, region) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'ads_region_day_batch',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads'
);

-- 批 ADS 同样落自身观测时间，便于与实时结果分别展示时效证据。
INSERT INTO ads_region_day_batch
SELECT biz_date, region, paid_order_count, gmv_cents, LOCALTIMESTAMP FROM region_day;
