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

    /** 执行器组：空表示任意实例可执行，非空只由同组实例领取 */
    private String executorGroup;

    /** 优先级：数值越大越先被领取，默认 0 */
    private Integer priority;

    /** 下次触发时刻（调度器维护，不在表单里直接编辑） */
    private LocalDateTime nextFireTime;

    /** 当前领取实例；为空表示未被领取 */
    private String owner;

    /** 租约到期时间：实例失联后由其他实例接管 */
    private LocalDateTime leaseUntil;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 本次领取的计划触发时刻，仅内存使用，用于业务日与幂等键 */
    @TableField(exist = false)
    private LocalDateTime claimedFireTime;
}
