package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flink_deployment")
public class FlinkDeployment extends BaseEntity {
    private String projectId;
    private String assetId;
    private Integer versionNumber;
    private String requestId;
    private String operatorId;
    private String clusterKey;
    private State state;
    private Integer rowVersion;
    private String engineJobId;
    private String engineState;
    private String savepointTriggerId;
    private String savepointPath;
    private String restoredFromRunId;
    private String message;
    private String activeAssetId;
    private LocalDateTime updateTime;

    public enum State {
        PREPARING, SUBMITTING, RECONCILING, SUBMITTED, CANCEL_REQUESTED, STOP_REQUESTED, STOPPED, FINISHED, FAILED, CANCELED;

        public boolean terminal() {
            return this == STOPPED || this == FINISHED || this == FAILED || this == CANCELED;
        }
    }
}
