USE data_khaos;

ALTER TABLE pipeline_instance
    ADD COLUMN target_datasource_id VARCHAR(32) NULL,
    ADD COLUMN target_database_name VARCHAR(200) NULL,
    ADD COLUMN published_at DATETIME(6) NULL COMMENT '目标 MySQL 分区写入时间 (UTC)',
    ADD KEY idx_pi_partition_publication
        (target_datasource_id, target_database_name, target_table, biz_date, published_at, id);

-- Older runs lack a physical target snapshot. Leave their evidence unavailable
-- instead of reconstructing it from the current task configuration.
CREATE OR REPLACE VIEW pipeline_partition_publication AS
SELECT id, target_datasource_id, target_database_name, target_table, biz_date,
       workflow_run_id, workflow_node_run_id, published_at, `rows`
FROM (
    SELECT p.*, ROW_NUMBER() OVER (
        PARTITION BY target_datasource_id, target_database_name, target_table, biz_date
        ORDER BY published_at DESC, id DESC
    ) AS publication_rank
    FROM pipeline_instance p
    WHERE status = 1 AND published_at IS NOT NULL
) ranked
WHERE publication_rank = 1;
