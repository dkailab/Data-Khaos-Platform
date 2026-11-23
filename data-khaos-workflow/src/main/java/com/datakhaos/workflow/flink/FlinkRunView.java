package com.datakhaos.workflow.flink;

import com.datakhaos.workflow.entity.FlinkDeployment;

import java.time.LocalDateTime;

public record FlinkRunView(String id, int version, String operatorId, FlinkDeployment.State state,
                           String engineJobId, String engineState, String message, boolean recoverable, String restoredFromRunId,
                           LocalDateTime createTime, LocalDateTime updateTime) {
    public static FlinkRunView of(FlinkDeployment row) {
        return new FlinkRunView(row.getId(), row.getVersionNumber(), row.getOperatorId(), row.getState(),
                row.getEngineJobId(), row.getEngineState(), row.getMessage(),
                row.getState() == FlinkDeployment.State.STOPPED && row.getSavepointPath() != null,
                row.getRestoredFromRunId(), row.getCreateTime(), row.getUpdateTime());
    }
}
