USE data_khaos;

CREATE TABLE IF NOT EXISTS platform_service_monitor (
    service_id VARCHAR(64) NOT NULL PRIMARY KEY,
    service_name VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    registered_count INT NOT NULL DEFAULT 0,
    reachable_count INT NOT NULL DEFAULT 0,
    unhealthy_streak INT NOT NULL DEFAULT 0,
    healthy_streak INT NOT NULL DEFAULT 0,
    sample_bucket BIGINT NOT NULL DEFAULT -1,
    checked_at DATETIME,
    reason VARCHAR(32),
    active_alert_id VARCHAR(32)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS platform_monitor_alert (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    service_id VARCHAR(64) NOT NULL,
    service_name VARCHAR(80) NOT NULL,
    opened_at DATETIME NOT NULL,
    acknowledged_at DATETIME,
    acknowledged_by VARCHAR(32),
    recovered_at DATETIME,
    reason VARCHAR(32) NOT NULL,
    INDEX idx_monitor_alert_opened (opened_at, id),
    INDEX idx_monitor_alert_recovery (recovered_at, opened_at)
) ENGINE=InnoDB;

INSERT IGNORE INTO platform_service_monitor (service_id, service_name) VALUES
    ('data-khaos-auth', '认证中心'),
    ('data-khaos-permission', '权限服务'),
    ('data-khaos-datasource', '数据源服务'),
    ('data-khaos-pipeline', '数据同步'),
    ('data-khaos-schedule', '任务调度'),
    ('data-khaos-workflow', '工作流编排'),
    ('data-khaos-dquality', '数据质量'),
    ('data-khaos-metadata', '元数据'),
    ('data-khaos-query', '查询服务'),
    ('data-khaos-mart', '数据集市');
