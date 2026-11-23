USE data_khaos;

CREATE TABLE IF NOT EXISTS query_job (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    datasource_id VARCHAR(32) NOT NULL,
    database_name VARCHAR(200),
    sql_text TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    create_time DATETIME NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    result_expires_at DATETIME,
    row_count INT,
    error_message VARCHAR(500),
    result_json MEDIUMTEXT,
    CONSTRAINT uk_query_job_request UNIQUE (user_id, request_id),
    INDEX idx_query_job_owner (user_id, create_time, id),
    INDEX idx_query_job_queue (status, create_time, id),
    INDEX idx_query_job_started (status, start_time),
    INDEX idx_query_job_end (end_time),
    INDEX idx_query_job_expiry (result_expires_at)
) ENGINE=InnoDB;
