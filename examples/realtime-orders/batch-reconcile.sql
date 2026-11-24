SET 'pipeline.name' = 'Orders bounded reconciliation @@RUN_ID@@';
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
  'table-name' = 'batch_snapshot_@@RUN_ID@@',
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
  PRIMARY KEY (biz_date, region) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'batch_ads_@@RUN_ID@@',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads'
);

INSERT INTO ads_region_day_batch SELECT * FROM region_day;
