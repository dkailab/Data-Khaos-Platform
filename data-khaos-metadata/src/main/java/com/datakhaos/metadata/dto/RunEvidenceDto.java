package com.datakhaos.metadata.dto;

import com.datakhaos.dquality.api.model.DqSnapshotDto;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PR3 运行证据：{@code runId -> nodeRunId -> pipelineInstanceId / snapshotId -> 输入输出资产及日期}。
 *
 * <p>只读聚合，不新增存储；结构血缘仍以 metadata 的 meta_table_lineage 为准，
 * 本对象只补充「哪个运行实际生产了这个分区」的追溯证据。</p>
 */
@Data
public class RunEvidenceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工作流运行（含 bizDate、requestId、触发方式） */
    private WorkflowRunDto run;

    /** 节点证据（按 DAG 顺序） */
    private List<NodeEvidenceDto> nodes;

    /** 该运行覆盖的业务日期（取自 run.bizDate，缺省为空） */
    private String bizDate;

    @Data
    public static class NodeEvidenceDto implements Serializable {

        private static final long serialVersionUID = 1L;

        private String nodeRunId;

        private String nodeCode;

        private String nodeName;

        /** PIPELINE / QUALITY / SCRIPT ... */
        private String nodeType;

        /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
        private String status;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Long durationMs;

        private String errorMessage;

        /** 关联外部实例类型 */
        private String refType;

        /** 关联外部实例ID（pipeline_instance.id 或 dquality_snapshot.id） */
        private String refId;

        /** 输入资产（库.表；来自任务声明与源 SQL 解析） */
        private List<String> inputAssets;

        /** 输出资产（库.表；实际写入的目标分区） */
        private List<String> outputAssets;

        /** 管道实例详情（refType=PIPELINE 时非空） */
        private PipelineInstanceDto pipeline;

        /** 质量快照详情（refType=QUALITY 时非空） */
        private DqSnapshotDto quality;
    }
}
