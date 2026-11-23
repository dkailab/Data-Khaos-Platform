USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_asset (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    project_id VARCHAR(32) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    current_version INT NOT NULL,
    current_mode VARCHAR(16),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_flink_asset_code (project_id, kind, code),
    KEY idx_flink_asset_list (project_id, kind, update_time, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS flink_asset_version (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    asset_id VARCHAR(32) NOT NULL,
    version_number INT NOT NULL,
    name VARCHAR(200) NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    restored_from INT,
    definition_json MEDIUMTEXT NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_flink_asset_version (asset_id, version_number)
) ENGINE=InnoDB;
