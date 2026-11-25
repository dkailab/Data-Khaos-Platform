USE data_khaos;

CREATE TABLE IF NOT EXISTS warehouse_api_version (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    project_id VARCHAR(32) NOT NULL,
    api_code VARCHAR(48) NOT NULL,
    api_version INT NOT NULL,
    api_name VARCHAR(100) NOT NULL,
    max_rows INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision INT NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    update_time DATETIME NOT NULL,
    published_by VARCHAR(32),
    published_at DATETIME,
    offlined_by VARCHAR(32),
    offlined_at DATETIME,
    CONSTRAINT uk_warehouse_api_version UNIQUE (project_id, api_code, api_version)
) ENGINE=InnoDB;
