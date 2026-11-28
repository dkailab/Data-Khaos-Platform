package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 外部运行时（当前为 DolphinScheduler）失败恢复操作。
 *
 * <p>同一节点运行与恢复动作只保留一行，唯一键用于并发互斥；重试时复用该行，
 * 保留操作人、远端实例与最近一次结果。</p>
 */
@Data
@TableName("workflow_external_recovery")
public class WorkflowExternalRecovery implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String runId;
    private String nodeRunId;
    private String wfId;
    private String projectId;
    private String runtime;
    private Long remoteProjectCode;
    private Long remoteInstanceId;
    private String action;
    private String status;
    private String operatorId;
    private String remoteStateBefore;
    private String remoteStateAfter;
    private Integer remoteRunTimes;
    private String localResumeStatus;
    private String message;
    private Integer pollCount;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
