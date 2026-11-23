package com.datakhaos.schedule.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务执行日志表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("schedule_job_log")
public class ScheduleJobLog extends BaseEntity {

    /** 任务ID */
    private String jobId;

    /** 0:运行中 1:成功 2:失败 */
    private Integer status;

    /** 计划触发时刻（cron 边界，区别于实际开始时间） */
    private LocalDateTime scheduledAt;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 错误信息 */
    private String errorMessage;

    /** 结果行数 */
    private Integer resultRows;
}
