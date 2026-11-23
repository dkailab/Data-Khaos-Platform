-- ============================================================
-- PaaS PR2B 迁移：按日重算订单数仓（写入策略 + 业务日期）
-- 目标：管道任务支持 REPLACE_BY_BIZ_DATE，实例记录业务日期与实际写入信息；
--       提供订单链路 MySQL 基线示例表（源订单 → ODS → DWD → DWS → ADS）。
-- 迁移方式: mysql -uroot -p data_khaos < db/migrate-paas-pr2b.sql
-- 幂等性：ADD COLUMN 重复执行会报列已存在，属预期；示例表用 CREATE TABLE IF NOT EXISTS。
-- ============================================================

USE data_khaos;

-- 1) 管道任务：写入策略与业务日期列
ALTER TABLE pipeline_task
    ADD COLUMN write_mode  VARCHAR(32) NULL COMMENT '写入策略 REPLACE_BY_BIZ_DATE/APPEND/TRUNCATE' AFTER config,
    ADD COLUMN date_column VARCHAR(64) NULL COMMENT '业务日期列（REPLACE_BY_BIZ_DATE 使用）' AFTER write_mode;

-- 2) 管道实例：业务日期与实际写入信息（运行证据）
ALTER TABLE pipeline_instance
    ADD COLUMN biz_date      VARCHAR(10)  NULL COMMENT '业务日期 YYYY-MM-DD' AFTER workflow_node_run_id,
    ADD COLUMN target_table  VARCHAR(128) NULL COMMENT '实际写入的目标表' AFTER biz_date,
    ADD COLUMN write_mode    VARCHAR(32)  NULL COMMENT '实际写入策略' AFTER target_table,
    ADD KEY idx_pi_biz (task_id, biz_date);

-- ============================================================
-- 3) 订单数仓 MySQL 基线示例（以日期列模拟逻辑分区）
-- ============================================================

-- 3.1 源订单（模拟业务库）
CREATE TABLE IF NOT EXISTS pr2_orders_src (
    order_id   VARCHAR(32)  NOT NULL COMMENT '订单号',
    region     VARCHAR(32)  NOT NULL COMMENT '地区',
    amount     DECIMAL(12,2) NOT NULL COMMENT '金额',
    status     VARCHAR(16)  NOT NULL COMMENT '状态 PAID/CANCEL',
    order_date VARCHAR(10)  NOT NULL COMMENT '业务日期 YYYY-MM-DD',
    PRIMARY KEY (order_id),
    KEY idx_src_date (order_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'PR2B 示例-源订单';

-- 3.2 ODS 订单
CREATE TABLE IF NOT EXISTS pr2_ods_orders (
    order_id   VARCHAR(32)  NOT NULL COMMENT '订单号',
    region     VARCHAR(32)  NOT NULL COMMENT '地区',
    amount     DECIMAL(12,2) NOT NULL COMMENT '金额',
    status     VARCHAR(16)  NOT NULL COMMENT '状态',
    order_date VARCHAR(10)  NOT NULL COMMENT '业务日期',
    PRIMARY KEY (order_id),
    KEY idx_ods_date (order_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'PR2B 示例-ODS 订单';

-- 3.3 DWD 支付订单
CREATE TABLE IF NOT EXISTS pr2_dwd_orders (
    order_id   VARCHAR(32)  NOT NULL COMMENT '订单号',
    region     VARCHAR(32)  NOT NULL COMMENT '地区',
    amount     DECIMAL(12,2) NOT NULL COMMENT '金额',
    order_date VARCHAR(10)  NOT NULL COMMENT '业务日期',
    PRIMARY KEY (order_id),
    KEY idx_dwd_date (order_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'PR2B 示例-DWD 支付订单';

-- 3.4 DWS 日地区汇总
CREATE TABLE IF NOT EXISTS pr2_dws_region_daily (
    stat_date   VARCHAR(10)   NOT NULL COMMENT '业务日期',
    region      VARCHAR(32)   NOT NULL COMMENT '地区',
    order_cnt   INT           NOT NULL DEFAULT 0 COMMENT '订单数',
    amount_sum  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '金额汇总',
    PRIMARY KEY (stat_date, region)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'PR2B 示例-DWS 日地区汇总';

-- 3.5 ADS 地区成交额
CREATE TABLE IF NOT EXISTS pr2_ads_region_gmv (
    stat_date VARCHAR(10)     NOT NULL COMMENT '业务日期',
    region    VARCHAR(32)     NOT NULL COMMENT '地区',
    gmv       DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '成交额',
    PRIMARY KEY (stat_date, region)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'PR2B 示例-ADS 地区成交额';

-- ============================================================
-- 4) 造数：两日（2026-11-17 / 2026-11-18）源订单
-- ============================================================
DELETE FROM pr2_orders_src WHERE order_date IN ('2026-11-17', '2026-11-18');
INSERT INTO pr2_orders_src (order_id, region, amount, status, order_date) VALUES
 ('PR2-1701', '华东', 100.00, 'PAID',   '2026-11-17'),
 ('PR2-1702', '华东', 200.50, 'PAID',   '2026-11-17'),
 ('PR2-1703', '华北',  59.00, 'PAID',   '2026-11-17'),
 ('PR2-1704', '华南', 320.00, 'CANCEL', '2026-11-17'),
 ('PR2-1801', '华东', 150.00, 'PAID',   '2026-11-18'),
 ('PR2-1802', '华北',  88.00, 'PAID',   '2026-11-18'),
 ('PR2-1803', '华南', 410.00, 'PAID',   '2026-11-18');

-- ============================================================
-- 5) 示例管道任务定义（REPLACE_BY_BIZ_DATE，源查询用 ${bizDate} 占位）
--    任务定义也可在「数据集成 → 管道任务」页面配置。
-- ============================================================
INSERT INTO pipeline_task
    (id, task_name, task_type, engine, source_ds_id, source_table, target_ds_id, target_table,
     source_query, field_mapping, config, write_mode, date_column, cron_expr, project_group_id, status,
     create_by, create_time, update_time)
VALUES
 ('pipe_pr2b_ods', 'PR2B-ODS订单', 'SYNC', 'DB_SYNC', 'ds_dw', 'pr2_orders_src', 'ds_app', 'pr2_ods_orders',
  'SELECT order_id, region, amount, status, order_date FROM pr2_orders_src WHERE order_date = ''${bizDate}''',
  NULL, NULL, 'REPLACE_BY_BIZ_DATE', 'order_date', NULL, NULL, 1, 'system', NOW(), NOW()),
 ('pipe_pr2b_dwd', 'PR2B-DWD支付订单', 'ETL', 'DB_SYNC', 'ds_dw', 'pr2_ods_orders', 'ds_app', 'pr2_dwd_orders',
  'SELECT order_id, region, amount, order_date FROM pr2_ods_orders WHERE order_date = ''${bizDate}'' AND status = ''PAID''',
  NULL, NULL, 'REPLACE_BY_BIZ_DATE', 'order_date', NULL, NULL, 1, 'system', NOW(), NOW()),
 ('pipe_pr2b_dws', 'PR2B-DWS日地区汇总', 'ETL', 'DB_SYNC', 'ds_dw', 'pr2_dwd_orders', 'ds_app', 'pr2_dws_region_daily',
  'SELECT order_date AS stat_date, region, COUNT(*) AS order_cnt, SUM(amount) AS amount_sum FROM pr2_dwd_orders WHERE order_date = ''${bizDate}'' GROUP BY order_date, region',
  NULL, NULL, 'REPLACE_BY_BIZ_DATE', 'stat_date', NULL, NULL, 1, 'system', NOW(), NOW()),
 ('pipe_pr2b_ads', 'PR2B-ADS地区成交额', 'ETL', 'DB_SYNC', 'ds_dw', 'pr2_dws_region_daily', 'ds_app', 'pr2_ads_region_gmv',
  'SELECT stat_date, region, amount_sum AS gmv FROM pr2_dws_region_daily WHERE stat_date = ''${bizDate}''',
  NULL, NULL, 'REPLACE_BY_BIZ_DATE', 'stat_date', NULL, NULL, 1, 'system', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    task_name = VALUES(task_name),
    source_query = VALUES(source_query),
    write_mode = VALUES(write_mode),
    date_column = VALUES(date_column),
    status = VALUES(status),
    update_time = NOW();
