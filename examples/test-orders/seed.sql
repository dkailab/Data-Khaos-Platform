-- Fixtures for the local test account. Business data lives in its own schema.
CREATE DATABASE IF NOT EXISTS dk_test_orders DEFAULT CHARACTER SET utf8mb4;
USE dk_test_orders;

CREATE TABLE IF NOT EXISTS demo_raw_orders (
    order_id BIGINT PRIMARY KEY COMMENT '订单编号',
    biz_date DATE NOT NULL COMMENT '业务日期',
    region VARCHAR(16) NOT NULL COMMENT '销售区域',
    channel VARCHAR(16) NOT NULL COMMENT '销售渠道',
    status VARCHAR(16) NOT NULL COMMENT '订单状态',
    amount DECIMAL(12,2) NOT NULL COMMENT '订单金额（元）',
    created_at DATETIME NOT NULL COMMENT '订单生成时间',
    KEY idx_biz_date (biz_date)
) COMMENT='演示订单源表（模拟数据）';

CREATE TABLE IF NOT EXISTS demo_ods_orders LIKE demo_raw_orders;
ALTER TABLE demo_ods_orders COMMENT='订单原始层（由同步作业生成）';

INSERT INTO demo_raw_orders (order_id, biz_date, region, channel, status, amount, created_at)
WITH RECURSIVE numbers AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < 420
)
SELECT n, DATE_ADD('2026-11-20', INTERVAL FLOOR((n - 1) / 60) DAY),
       ELT(MOD(n - 1, 5) + 1, '华北', '华东', '华南', '华中', '西南'),
       ELT(MOD(FLOOR((n - 1) / 5), 3) + 1, '商城', '门店', '合作渠道'),
       CASE WHEN MOD(n, 14) = 0 THEN 'REFUNDED' WHEN MOD(n, 9) = 0 THEN 'CANCELLED' ELSE 'PAID' END,
       25 + MOD(n * 1379, 98000) / 100,
       TIMESTAMP(DATE_ADD('2026-11-20', INTERVAL FLOOR((n - 1) / 60) DAY), SEC_TO_TIME(28800 + MOD(n * 2231, 46800)))
FROM numbers
ON DUPLICATE KEY UPDATE order_id = demo_raw_orders.order_id;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW demo_dwd_paid_orders AS
SELECT order_id, biz_date, region, channel, amount, created_at
FROM demo_ods_orders WHERE status = 'PAID';

CREATE OR REPLACE SQL SECURITY INVOKER VIEW demo_dws_daily_sales AS
SELECT biz_date, region, channel, COUNT(*) AS paid_count, SUM(amount) AS gmv
FROM demo_dwd_paid_orders GROUP BY biz_date, region, channel;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW demo_ads_region_sales AS
SELECT biz_date, region, SUM(paid_count) AS paid_count, SUM(gmv) AS gmv
FROM demo_dws_daily_sales GROUP BY biz_date, region;

USE data_khaos;
START TRANSACTION;
INSERT INTO sys_organization (id, org_name, org_code, org_type, status)
VALUES ('dk_demo_orders_org', '本地测试', 'DK_DEMO_ORDERS', 'DEPT', 1)
ON DUPLICATE KEY UPDATE id = sys_organization.id;

INSERT INTO sg_project_group (id, org_id, project_name, project_code, status)
VALUES ('dk_demo_orders', 'dk_demo_orders_org', '订单数仓 · 测试空间', 'DK_DEMO_ORDERS', 1)
ON DUPLICATE KEY UPDATE id = sg_project_group.id;

INSERT INTO sg_project_role (id, org_id, project_group_id, role_name, role_code, capability_flags, status)
VALUES ('dk_demo_orders_dev', 'dk_demo_orders_org', 'dk_demo_orders', '测试开发者', 'DK_DEMO_ORDERS_DEV',
        JSON_ARRAY('task:develop', 'task:schedule', 'meta:browse', 'query:execute',
                   'quality:browse', 'quality:manage', 'quality:run'), 1)
ON DUPLICATE KEY UPDATE capability_flags = IF(project_group_id = 'dk_demo_orders',
                                                 VALUES(capability_flags), capability_flags);

INSERT INTO sg_project_group_member (id, project_group_id, user_id, project_role_id, is_primary)
SELECT 'dk_demo_orders_member', 'dk_demo_orders', id, 'dk_demo_orders_dev', 0
FROM sys_user WHERE username = 'test' AND status = 1
ON DUPLICATE KEY UPDATE id = sg_project_group_member.id;
COMMIT;
