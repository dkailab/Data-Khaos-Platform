USE data_khaos;

ALTER TABLE workflow_run
    ADD COLUMN heartbeat_at DATETIME NULL,
    ADD COLUMN recovery_code VARCHAR(40) NULL,
    ADD COLUMN recovery_resolved_at DATETIME NULL,
    ADD COLUMN recovery_resolved_by VARCHAR(32) NULL,
    ADD COLUMN recovery_note VARCHAR(500) NULL,
    ADD COLUMN alert_acknowledged_at DATETIME NULL,
    ADD COLUMN alert_acknowledged_by VARCHAR(32) NULL,
    ADD INDEX idx_wf_recover_status_time (run_status, heartbeat_at, create_time),
    ADD INDEX idx_wf_project_alerts (project_group_id, run_status, alert_acknowledged_at, end_time, id);
