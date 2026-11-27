SET 'pipeline.name' = 'Orders realtime warehouse';
SET 'parallelism.default' = '1';
SET 'execution.checkpointing.interval' = '2 s';
SET 'table.local-time-zone' = 'Asia/Shanghai';
SET 'table.dml-sync' = 'false';

CREATE TABLE ods_orders (
  `before` ROW<order_id BIGINT, region STRING, amount_cents BIGINT, status STRING, paid_epoch_ms BIGINT, change_version BIGINT>,
  `after` ROW<order_id BIGINT, region STRING, amount_cents BIGINT, status STRING, paid_epoch_ms BIGINT, change_version BIGINT>,
  op STRING
) WITH (
  'connector' = 'kafka',
  'topic' = 'orders.ods.v1',
  'properties.bootstrap.servers' = 'kafka:9092',
  'properties.group.id' = 'orders-warehouse-v1',
  'properties.isolation.level' = 'read_committed',
  'scan.startup.mode' = 'earliest-offset',
  'format' = 'json'
);

CREATE TEMPORARY VIEW normalized_orders AS
SELECT COALESCE(`after`.order_id, `before`.order_id) AS order_id,
       COALESCE(`after`.region, `before`.region) AS region,
       COALESCE(`after`.amount_cents, `before`.amount_cents) AS amount_cents,
       COALESCE(`after`.status, `before`.status) AS status,
       COALESCE(`after`.paid_epoch_ms, `before`.paid_epoch_ms) AS paid_epoch_ms,
       COALESCE(`after`.change_version, `before`.change_version) AS change_version,
       CASE WHEN op = 'd' THEN 1 ELSE 0 END AS is_deleted
FROM ods_orders
WHERE op IN ('r', 'c', 'u', 'd');

-- A delete wins a tie with its last row version. Replay of an older version cannot resurrect it.
CREATE TEMPORARY VIEW latest_orders AS
SELECT order_id, region, amount_cents, status, paid_epoch_ms, change_version, is_deleted
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY order_id ORDER BY change_version DESC, is_deleted DESC
  ) AS rn
  FROM normalized_orders
)
WHERE rn = 1;

-- INCLUDE paid-order-views.sql

CREATE TABLE dwd_paid_orders (
  order_id BIGINT,
  biz_date DATE,
  minute_start TIMESTAMP(3),
  region STRING,
  amount_cents BIGINT,
  change_version BIGINT,
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'dwd_paid_orders',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads',
  'sink.buffer-flush.interval' = '1 s'
);

CREATE TABLE dws_region_minute (
  biz_date DATE,
  minute_start TIMESTAMP(3),
  region STRING,
  paid_order_count BIGINT,
  gmv_cents DECIMAL(38,0),
  PRIMARY KEY (biz_date, minute_start, region) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'dws_region_minute',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads',
  'sink.buffer-flush.interval' = '1 s'
);

CREATE TABLE ads_region_day (
  biz_date DATE,
  region STRING,
  paid_order_count BIGINT,
  gmv_cents DECIMAL(38,0),
  observed_at TIMESTAMP(3),
  PRIMARY KEY (biz_date, region) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'ads_region_day',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads',
  'sink.buffer-flush.interval' = '1 s'
);

-- 结果级推进：业务日每次汇总后覆盖一行，供读侧核对「表内容」与「推进信号」是否一致。
CREATE TABLE ads_region_day_progress (
  biz_date DATE,
  advanced_at TIMESTAMP(3),
  region_count BIGINT,
  paid_order_count BIGINT,
  gmv_cents DECIMAL(38,0),
  PRIMARY KEY (biz_date) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'ads_region_day_progress',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads',
  'sink.buffer-flush.interval' = '1 s'
);

-- 作业级推进：按业务日统计已消费的 CDC 事件，独立于成交状态。
-- 全是取消单（ADS 无行）时该表仍有行，据此区分「无成交」与「尚未产出」。
CREATE TABLE ads_region_day_events (
  biz_date DATE,
  advanced_at TIMESTAMP(3),
  event_count BIGINT,
  max_event_time TIMESTAMP(3),
  PRIMARY KEY (biz_date) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:mysql://mysql:3306/orders_serving?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
  'table-name' = 'ads_region_day_events',
  'username' = 'ads_writer',
  'password' = 'local-orders-ads',
  'sink.buffer-flush.interval' = '1 s'
);

-- SQL Client -f executes one statement at a time in this Flink release.
BEGIN STATEMENT SET;
  INSERT INTO dwd_paid_orders SELECT * FROM dwd_paid;
  INSERT INTO dws_region_minute
    SELECT biz_date, minute_start, region, COUNT(*), SUM(CAST(amount_cents AS DECIMAL(38,0)))
    FROM dwd_paid GROUP BY biz_date, minute_start, region;
  -- observed_at 取作业写入时刻（会话时区 Asia/Shanghai），是契约中可复核的结果观测时间。
  INSERT INTO ads_region_day
    SELECT biz_date, region, paid_order_count, gmv_cents, LOCALTIMESTAMP FROM region_day;
  INSERT INTO ads_region_day_progress
    SELECT biz_date, LOCALTIMESTAMP, COUNT(*), SUM(paid_order_count), SUM(gmv_cents)
    FROM region_day GROUP BY biz_date;
  INSERT INTO ads_region_day_events
    SELECT CAST(TO_TIMESTAMP_LTZ(paid_epoch_ms, 3) AS DATE) AS biz_date,
           LOCALTIMESTAMP,
           COUNT(*),
           CAST(MAX(TO_TIMESTAMP_LTZ(paid_epoch_ms, 3)) AS TIMESTAMP(3))
    FROM normalized_orders
    GROUP BY CAST(TO_TIMESTAMP_LTZ(paid_epoch_ms, 3) AS DATE);
END;
