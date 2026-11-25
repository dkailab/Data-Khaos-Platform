USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_alert_outbox (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    alert_id VARCHAR(32) NOT NULL,
    run_id VARCHAR(32) NOT NULL,
    receiver_id VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    delivered_at DATETIME,
    UNIQUE KEY uk_flink_alert_event (alert_id, event_type),
    KEY idx_flink_alert_outbox_due (delivered_at, next_attempt_at, id)
) ENGINE=InnoDB;
