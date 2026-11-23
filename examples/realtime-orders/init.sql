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

CREATE TABLE orders_serving.ads_region_day (
  biz_date DATE NOT NULL,
  region VARCHAR(32) NOT NULL,
  paid_order_count BIGINT NOT NULL,
  gmv_cents DECIMAL(38,0) NOT NULL,
  PRIMARY KEY (biz_date, region)
) ENGINE=InnoDB;

-- Batch reconciliation uses a fixed snapshot and its own serving table.
CREATE TABLE orders_serving.batch_order_snapshot LIKE orders_source.orders;
CREATE TABLE orders_serving.ads_region_day_batch LIKE orders_serving.ads_region_day;
