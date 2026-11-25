USE data_khaos;

CREATE TABLE IF NOT EXISTS warehouse_api_credential (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    api_version_id VARCHAR(32) NOT NULL,
    app_name VARCHAR(100) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    issued_by VARCHAR(32) NOT NULL,
    issued_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_by VARCHAR(32),
    revoked_at DATETIME,
    CONSTRAINT uk_warehouse_api_token UNIQUE (token_digest),
    INDEX idx_warehouse_api_credential_version (api_version_id, issued_at, id)
) ENGINE=InnoDB;
