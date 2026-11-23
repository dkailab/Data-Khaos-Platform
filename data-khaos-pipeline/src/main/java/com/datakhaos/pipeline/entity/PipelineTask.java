package com.datakhaos.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管道任务定义
 */
@Data
@TableName("pipeline_task")
public class PipelineTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
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

    /** 字段映射（JSON，选填） */
    private String fieldMapping;

    /** 引擎扩展配置（JSON） */
    private String config;

    /** 写入策略 REPLACE_BY_BIZ_DATE/APPEND/TRUNCATE（空=APPEND） */
    private String writeMode;

    /** 业务日期列（REPLACE_BY_BIZ_DATE 使用） */
    private String dateColumn;

    /** 定时表达式（空=仅手动） */
    private String cronExpr;

    /** 归属项目组（业务线-项目组隔离，空=不限制） */
    private String projectGroupId;

    /** 1=启用 0=停用 */
    private Integer status;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}