-- Execute in the selected MySQL serving database.
CREATE TABLE IF NOT EXISTS dk_materialization_version (
    target_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_date DATE NOT NULL,
    instance_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (target_table, biz_date)
) ENGINE=InnoDB;
