USE data_khaos;

ALTER TABLE pipeline_instance
    ADD COLUMN materialization_generation CHAR(36) NULL
    COMMENT '同事务写入目标数据库的受管分区版本';
