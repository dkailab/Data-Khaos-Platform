package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流运行实例
 */
@Data
@TableName("workflow_run")
public class WorkflowRun implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 工作流ID */
    private String wfId;

    /** 工作流名称（冗余展示） */
    private String wfName;

    /** 触发类型: MANUAL / SCHEDULE */
    private String triggerType;

    /** 运行状态: PENDING / RUNNING / SUCCESS / FAILED / STOP */
    private String runStatus;

    /** 触发时注入参数（JSON） */
    private String triggerParams;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 总耗时（毫秒） */
    private Long durationMs;

    private String errorMessage;

    /** 关联调度任务ID（SCHEDULE 触发时写入） */
    private String scheduleJobId;

    /** 关联调度执行日志ID（SCHEDULE 触发时写入） */
    private String scheduleLogId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}