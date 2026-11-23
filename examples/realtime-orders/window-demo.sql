SET 'pipeline.name' = 'Immutable events window acceptance';
SET 'execution.runtime-mode' = 'streaming';
SET 'parallelism.default' = '1';
SET 'sql-client.execution.result-mode' = 'tableau';

CREATE TEMPORARY TABLE immutable_payment_events (
  event_time TIMESTAMP(3),
  region STRING,
  amount_cents BIGINT,
  WATERMARK FOR event_time AS event_time - INTERVAL '1' MINUTE
) WITH (
  'connector' = 'filesystem',
  'path' = 'file:///lab/window-events.csv',
  'format' = 'csv'
);

SELECT 'TUMBLE' AS window_kind, window_start, window_end, region, SUM(amount_cents) AS cents
FROM TABLE(TUMBLE(TABLE immutable_payment_events, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, region
UNION ALL
SELECT 'HOP', window_start, window_end, region, SUM(amount_cents)
FROM TABLE(HOP(TABLE immutable_payment_events, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '2' MINUTE))
GROUP BY window_start, window_end, region
UNION ALL
SELECT 'CUMULATE', window_start, window_end, region, SUM(amount_cents)
FROM TABLE(CUMULATE(TABLE immutable_payment_events, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '2' MINUTE))
GROUP BY window_start, window_end, region;
