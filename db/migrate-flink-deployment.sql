USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_deployment (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    project_id VARCHAR(32) NOT NULL,
    asset_id VARCHAR(32) NOT NULL,
    version_number INT NOT NULL,
    request_id CHAR(36) NOT NULL,
    operator_id VARCHAR(32) NOT NULL,
    cluster_key CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    row_version INT NOT NULL DEFAULT 0,
    engine_job_id CHAR(32),
    engine_state VARCHAR(24),
    savepoint_trigger_id CHAR(32),
    savepoint_path VARCHAR(2048),
    restored_from_run_id VARCHAR(32),
    message VARCHAR(500),
    active_asset_id VARCHAR(32),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_flink_run_request (project_id, request_id),
    UNIQUE KEY uk_flink_run_active (active_asset_id),
    KEY idx_flink_run_list (project_id, asset_id, create_time, id)
) ENGINE=InnoDB;
