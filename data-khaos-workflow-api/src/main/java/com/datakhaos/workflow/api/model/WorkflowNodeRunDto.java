package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流节点运行实例视图（跨服务传输用，字段与 workflow_node_run 表对齐）。
 */
@Data
public class WorkflowNodeRunDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String runId;

    private String wfId;

    private String nodeCode;

    private String nodeName;

    private String nodeType;

    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Integer resultRows;

    private String logText;

    private String errorMessage;

    /** 关联外部实例类型，如 PIPELINE */
    private String refType;

    /** 关联外部实例ID，如 pipeline_instance.id */
    private String refId;

    private LocalDateTime createTime;
}
