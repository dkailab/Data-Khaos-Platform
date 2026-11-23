USE data_khaos;

CREATE TABLE IF NOT EXISTS workflow_backfill_batch (
    wf_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    request_hash CHAR(64) NULL,
    legacy_conflict TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (wf_id, request_id)
) ENGINE=InnoDB;

-- Recover the original interval from 2A child requests. Legacy keys already
-- used with different intervals must be replaced with a fresh key.
INSERT INTO workflow_backfill_batch (wf_id, request_id, from_date, to_date, request_hash, legacy_conflict)
SELECT wf_id, batch_id, MIN(SUBSTRING_INDEX(date_range, '|', 1)),
       MIN(SUBSTRING_INDEX(date_range, '|', -1)), MIN(params_hash),
       COUNT(DISTINCT date_range) > 1 OR COUNT(DISTINCT params_hash) > 1
FROM (
    SELECT wf_id, LEFT(request_id, CHAR_LENGTH(request_id) - 11) AS batch_id,
           RIGHT(request_params, 21) AS date_range,
           SHA2(REPLACE(LEFT(request_params, CHAR_LENGTH(request_params) - CHAR_LENGTH(request_id) - 21),
                        CONCAT('bizDate=', biz_date, CHAR(10)), ''), 256) AS params_hash
    FROM workflow_run
    WHERE biz_date IS NOT NULL
      AND RIGHT(request_id, 11) = CONCAT(':', biz_date)
      AND RIGHT(request_params, CHAR_LENGTH(request_id) + 21) =
          CONCAT('|backfill=', LEFT(request_id, CHAR_LENGTH(request_id) - 11), '|', RIGHT(request_params, 21))
      AND RIGHT(request_params, 21) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[|][0-9]{4}-[0-9]{2}-[0-9]{2}$'
) legacy
GROUP BY wf_id, batch_id
ON DUPLICATE KEY UPDATE request_hash = workflow_backfill_batch.request_hash;
