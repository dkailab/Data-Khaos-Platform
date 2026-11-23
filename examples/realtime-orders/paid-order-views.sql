CREATE TEMPORARY VIEW dwd_paid AS
SELECT order_id,
       CAST(TO_TIMESTAMP_LTZ(paid_epoch_ms, 3) AS DATE) AS biz_date,
       FLOOR(CAST(TO_TIMESTAMP_LTZ(paid_epoch_ms, 3) AS TIMESTAMP(3)) TO MINUTE) AS minute_start,
       region, amount_cents, change_version
FROM latest_orders
WHERE status = 'PAID' AND is_deleted = 0;

CREATE TEMPORARY VIEW region_day AS
SELECT biz_date, region, COUNT(*) AS paid_order_count,
       SUM(CAST(amount_cents AS DECIMAL(38,0))) AS gmv_cents
FROM dwd_paid GROUP BY biz_date, region;
