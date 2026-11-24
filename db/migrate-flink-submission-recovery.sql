USE data_khaos;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
            AND table_name = 'flink_deployment' AND column_name = 'gateway_session_handle'),
    'DO 0',
    'ALTER TABLE flink_deployment ADD COLUMN gateway_session_handle CHAR(36) NULL'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
            AND table_name = 'flink_deployment' AND column_name = 'gateway_operation_handle'),
    'DO 0',
    'ALTER TABLE flink_deployment ADD COLUMN gateway_operation_handle CHAR(36) NULL'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
