USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_checkpoint_watch (
    run_id VARCHAR(32) NOT NULL PRIMARY KEY,
    last_checkpoint_id BIGINT NOT NULL,
    ended BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_checkpoint_alert (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    first_failed_checkpoint_id BIGINT NOT NULL,
    last_failed_checkpoint_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at DATETIME NOT NULL,
    closed_at DATETIME,
    recovery_checkpoint_id BIGINT,
    KEY idx_flink_alert_run (run_id, status, opened_at, id)
) ENGINE=InnoDB;
