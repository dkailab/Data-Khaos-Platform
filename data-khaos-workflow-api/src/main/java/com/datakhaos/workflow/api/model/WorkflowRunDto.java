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

    private String projectGroupId;

    /** 触发类型 MANUAL/SCHEDULE */
    private String triggerType;

    /** PENDING / RUNNING / SUCCESS / FAILED / STOP / RECOVERY_REQUIRED */
    private String runStatus;

    private String triggerParams;

    /** 业务日期 YYYY-MM-DD（旧记录可为空） */
    private String bizDate;

    /** 请求幂等键 */
    private String requestId;

    /** 计划触发时刻（调度触发写入） */
    private LocalDateTime scheduledAt;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String errorMessage;

    /** 拒绝码（RUN_ALREADY_ACTIVE / REQUEST_CONFLICT / PROJECT_FORBIDDEN / INVALID_DATE / RANGE_EXCEEDED / FORBIDDEN） */
    private String rejectCode;

    /** 拒绝原因 */
    private String rejectMessage;

    /** 关联调度任务ID（SCHEDULE 触发时写入） */
    private String scheduleJobId;

    /** 关联调度执行日志ID */
    private String scheduleLogId;

    private LocalDateTime createTime;
}
