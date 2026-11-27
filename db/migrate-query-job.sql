-- ============================================================
-- 异步查询作业表（query_job）
--
-- 背景：db/init.sql（达梦）早已包含 query_job，但 db/mysql-init.sql 缺失，
-- 导致 MySQL 部署上 QueryJobWorker 轮询时报 "Table 'query_job' doesn't exist"。
-- 本脚本用于存量库补齐，与 db/mysql-init.sql 定义一致。
-- ============================================================

USE data_khaos;

CREATE TABLE IF NOT EXISTS query_job (
    id                VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id           VARCHAR(32) NOT NULL COMMENT '提交用户',
    request_id        VARCHAR(36) NOT NULL COMMENT '幂等请求ID',
    datasource_id     VARCHAR(32) NOT NULL COMMENT '数据源ID',
    database_name     VARCHAR(200) COMMENT '数据库',
    sql_text          LONGTEXT NOT NULL COMMENT 'SQL文本',
    status            VARCHAR(20) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELED',
    create_time       DATETIME NOT NULL COMMENT '创建时间',
    start_time        DATETIME COMMENT '开始时间',
    end_time          DATETIME COMMENT '结束时间',
    result_expires_at DATETIME COMMENT '结果过期时间',
    row_count         INT COMMENT '结果行数',
    error_message     VARCHAR(500) COMMENT '错误信息',
    result_json       LONGTEXT COMMENT '结果快照JSON',
    UNIQUE KEY uk_query_job_request (user_id, request_id),
    KEY idx_query_job_owner (user_id, create_time, id),
    KEY idx_query_job_queue (status, create_time, id),
    KEY idx_query_job_started (status, start_time),
    KEY idx_query_job_end (end_time),
    KEY idx_query_job_expiry (result_expires_at)
) ENGINE=InnoDB COMMENT='异步查询作业表';
