USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_kafka_watch (
    run_id VARCHAR(32) NOT NULL PRIMARY KEY,
    revision_id VARCHAR(32) NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    vertex_name VARCHAR(240) NOT NULL,
    high_threshold BIGINT NOT NULL,
    low_threshold BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    sampled_at_ms BIGINT NOT NULL DEFAULT 0,
    last_lag BIGINT,
    high_streak INT NOT NULL DEFAULT 0,
    low_streak INT NOT NULL DEFAULT 0,
    KEY idx_flink_kafka_watch_scan (enabled, run_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_kafka_alert (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    vertex_name VARCHAR(240) NOT NULL,
    peak_lag BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at DATETIME NOT NULL,
    closed_at DATETIME,
    KEY idx_flink_kafka_alert_run (run_id, status, opened_at, id)
) ENGINE=InnoDB;
