package com.datakhaos.schedule.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务定义表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("schedule_job")
public class ScheduleJob extends BaseEntity {

    /** 任务名称 */
    private String jobName;

    /** SQL / QUALITY / SYNC / REFRESH / PUSH */
    private String jobType;

    /** 分组 */
    private String jobGroup;

    /** Cron 表达式 */
    private String cronExpression;

    /** 数据源ID */
    private String datasourceId;

    /** 执行SQL */
    private String targetSql;

    /** 目标表 */
    private String targetTable;

    /** 参数（JSON） */
    private String params;

    /** 关联对象类型 WORKFLOW/PIPELINE（可空，空时回退 params.workflowId / params.pipelineTaskId） */
    private String refType;

    /** 关联对象ID（工作流ID 或 管道任务ID） */
    private String refId;

    /** 0:停用 1:启用 */
    private Integer status;

    /** 失败重试次数 */
    private Integer retryCount;

    /** 重试间隔（秒） */
    private Integer retryInterval;

    /** 超时（秒） */
    private Integer timeout;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
