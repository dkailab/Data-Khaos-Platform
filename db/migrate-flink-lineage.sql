-- ============================================================
-- 实时血缘：Flink SQL 表级上下游
--
-- 解析 INSERT INTO ... SELECT 与 DDL WITH 选项，按「作业 + 版本」保存。
-- 名称解析到元数据资产后，额外回写 meta_table_lineage（relation_type=FLINK）。
-- 与 flink_asset 一致，Flink 相关表只走迁移脚本，不进 db/init.sql。
-- ============================================================

USE data_khaos;

CREATE TABLE IF NOT EXISTS flink_table_lineage (
    id                VARCHAR(32)  NOT NULL PRIMARY KEY,
    project_id        VARCHAR(32)  NOT NULL,
    asset_id          VARCHAR(32)  NOT NULL,
    asset_version     INT          NOT NULL,
    run_id            VARCHAR(32)  NULL,
    source_name       VARCHAR(200) NOT NULL,
    source_asset_id   VARCHAR(32)  NULL,
    target_name       VARCHAR(200) NOT NULL,
    target_asset_id   VARCHAR(32)  NULL,
    relation_type     VARCHAR(32)  NOT NULL DEFAULT 'FLINK',
    create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_flink_lineage (asset_id, asset_version, source_name, target_name),
    KEY idx_flink_lineage_source (source_asset_id),
    KEY idx_flink_lineage_target (target_asset_id)
) ENGINE=InnoDB COMMENT='Flink SQL 表级血缘';
