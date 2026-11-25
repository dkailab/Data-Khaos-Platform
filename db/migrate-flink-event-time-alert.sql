USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_event_time_watch (
    run_id VARCHAR(32) NOT NULL PRIMARY KEY,
    revision_id VARCHAR(32) NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    sampled_at_ms BIGINT NOT NULL DEFAULT 0,
    baseline_json TEXT,
    stalled_intervals INT NOT NULL DEFAULT 0,
    next_slot INT NOT NULL DEFAULT 0,
    KEY idx_flink_event_time_scan (enabled, run_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_event_time_point (
    run_id VARCHAR(32) NOT NULL,
    slot INT NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    sampled_at_ms BIGINT NOT NULL,
    watermark_ms BIGINT,
    input_delta BIGINT,
    diagnosis VARCHAR(16) NOT NULL,
    PRIMARY KEY (run_id, slot)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_event_time_alert (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    vertex_id CHAR(32) NOT NULL,
    stalled_watermark_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at DATETIME NOT NULL,
    closed_at DATETIME,
    KEY idx_flink_event_alert_run (run_id, status, opened_at, id)
) ENGINE=InnoDB;
