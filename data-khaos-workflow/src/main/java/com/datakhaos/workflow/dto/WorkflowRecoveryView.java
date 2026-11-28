package com.datakhaos.workflow.dto;

import com.datakhaos.workflow.entity.WorkflowExternalRecovery;

import java.time.LocalDateTime;

/**
 * 失败节点恢复结果：远端状态与本地下游续跑状态分开返回，避免把远端成功误报为本地成功。
 */
public record WorkflowRecoveryView(
        String runId,
        String nodeRunId,
        String action,
        String status,
        Long remoteProjectCode,
        Long remoteInstanceId,
        String remoteStateBefore,
        String remoteStateAfter,
        Integer remoteRunTimes,
        String operatorId,
        String localResumeStatus,
        String message,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static WorkflowRecoveryView of(WorkflowExternalRecovery recovery) {
        return new WorkflowRecoveryView(recovery.getRunId(), recovery.getNodeRunId(), recovery.getAction(),
                recovery.getStatus(), recovery.getRemoteProjectCode(), recovery.getRemoteInstanceId(),
                recovery.getRemoteStateBefore(), recovery.getRemoteStateAfter(), recovery.getRemoteRunTimes(),
                recovery.getOperatorId(), recovery.getLocalResumeStatus(), recovery.getMessage(),
                recovery.getCreateTime(), recovery.getUpdateTime());
    }
}
