USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_pressure_watch (
    run_id VARCHAR(32) NOT NULL PRIMARY KEY,
    sampled_at_ms BIGINT NOT NULL,
    sample_json MEDIUMTEXT NOT NULL,
    high_streak INT NOT NULL,
    low_streak INT NOT NULL,
    ended BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_pressure_alert (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    peak_percent DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at DATETIME NOT NULL,
    closed_at DATETIME,
    KEY idx_flink_pressure_alert_run (run_id, status, opened_at, id)
) ENGINE=InnoDB;
