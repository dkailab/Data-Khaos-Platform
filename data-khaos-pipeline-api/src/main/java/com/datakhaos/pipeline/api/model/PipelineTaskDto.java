package com.datakhaos.pipeline.api.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 管道任务定义视图（跨服务传输用，字段与 pipeline_task 表对齐）。
 * PR3 用于把节点声明的源/目标表并入运行证据。
 */
@Data
public class PipelineTaskDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String taskName;

    /** SYNC=同步 ETL=加工 */
    private String taskType;

    /** 执行引擎 DB_SYNC/DATAX/SEATUNNEL */
    private String engine;

    private String sourceDsId;

    private String sourceTable;

    private String targetDsId;

    private String targetTable;

    /** 源查询（自定义 SQL，选填） */
    private String sourceQuery;

    /** 写入策略 REPLACE_BY_BIZ_DATE/APPEND/TRUNCATE */
    private String writeMode;

    /** 业务日期列（REPLACE_BY_BIZ_DATE 使用） */
    private String dateColumn;

    /** 归属项目组（空=不限制） */
    private String projectGroupId;

    /** 1=启用 0=停用 */
    private Integer status;
}
