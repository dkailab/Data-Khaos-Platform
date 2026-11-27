package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Flink 算子阶段计数快照。
 *
 * <p>供 dquality 的 PIPELINE_COUNT 规则做相邻阶段对账。
 * 每个采样周期为每个运行中的算子写一条，stage 取算子名，records_in/out 取累计计数器。
 */
@Data
@TableName("dk_pipeline_stage_count")
public class FlinkStageCount {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String jobId;
    /** 同一轮采样共用，质量侧据此只取最新一批，避免跨轮次混算。 */
    private String sampleBatch;
    private String vertexId;
    private String stage;
    /** 作业拓扑顺序，0 为最上游。 */
    private int stageOrder;
    private LocalDateTime sampledAt;
    private long recordsIn;
    private long recordsOut;
}
