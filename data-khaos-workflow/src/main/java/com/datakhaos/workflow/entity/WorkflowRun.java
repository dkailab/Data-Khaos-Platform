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

    /** 受理时的项目归属。存量实例可为空。 */
    private String projectGroupId;

    /** 工作流名称（冗余展示） */
    private String wfName;

    /** 触发类型: MANUAL / SCHEDULE */
    private String triggerType;

    /** 运行状态: PENDING / RUNNING / SUCCESS / FAILED / STOP / RECOVERY_REQUIRED */
    private String runStatus;

    /** 触发时注入参数（JSON） */
    private String triggerParams;

    /** 业务日期 YYYY-MM-DD（旧触发为空） */
    private String bizDate;

    /** 请求幂等键（旧触发为空） */
    private String requestId;

    /** 规范化请求参数（供同键异参诊断） */
    private String requestParams;

    /** 规范化请求参数 SHA-256（同键异参校验） */
    private String requestHash;

    /** 计划触发时刻（调度触发写入，cron 边界） */
    private LocalDateTime scheduledAt;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 总耗时（毫秒） */
    private Long durationMs;

    private String errorMessage;

    /** 拒绝码（RUN_ALREADY_ACTIVE / REQUEST_CONFLICT / PROJECT_FORBIDDEN / INVALID_DATE / RANGE_EXCEEDED / FORBIDDEN） */
    private String rejectCode;

    /** 拒绝原因 */
    private String rejectMessage;

    /** 关联调度任务ID（SCHEDULE 触发时写入） */
    private String scheduleJobId;

    /** 关联调度执行日志ID（SCHEDULE 触发时写入） */
    private String scheduleLogId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
