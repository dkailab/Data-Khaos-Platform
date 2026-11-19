package com.datakhaos.pipeline.api.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管道执行实例视图（跨服务传输用，字段与 pipeline_instance 表对齐）。
 */
@Data
public class PipelineInstanceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String taskId;

    /** 执行引擎 */
    private String engine;

    /** 触发方式 MANUAL/CRON/SCHEDULE/WORKFLOW */
    private String triggerType;

    /** 0=运行中 1=成功 2=失败 */
    private Integer status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    /** 影响行数 */
    private Long rows;

    private String errorMessage;

    private String worker;

    /** 关联工作流运行ID（WORKFLOW 触发时写入） */
    private String workflowRunId;

    /** 关联工作流节点运行ID */
    private String workflowNodeRunId;

    private LocalDateTime createTime;
}
