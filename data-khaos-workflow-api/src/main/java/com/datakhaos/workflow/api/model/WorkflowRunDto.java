package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流运行实例视图（跨服务传输用，字段与 workflow_run 表对齐）。
 */
@Data
public class WorkflowRunDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String wfId;

    private String wfName;

    /** 触发类型 MANUAL/SCHEDULE */
    private String triggerType;

    /** PENDING / RUNNING / SUCCESS / FAILED / STOP */
    private String runStatus;

    private String triggerParams;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String errorMessage;

    /** 关联调度任务ID（SCHEDULE 触发时写入） */
    private String scheduleJobId;

    /** 关联调度执行日志ID */
    private String scheduleLogId;

    private LocalDateTime createTime;
}
