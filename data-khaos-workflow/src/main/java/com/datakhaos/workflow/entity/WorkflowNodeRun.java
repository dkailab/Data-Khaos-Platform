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
 * 工作流节点运行实例（含执行日志）
 */
@Data
@TableName("workflow_node_run")
public class WorkflowNodeRun implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 工作流运行ID */
    private String runId;

    /** 工作流ID */
    private String wfId;

    /** 节点编码 */
    private String nodeCode;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型 */
    private String nodeType;

    /** 状态: PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 影响行数 */
    private Integer resultRows;

    /** 执行日志 */
    private String logText;

    private String errorMessage;

    /** 关联外部实例类型，如 PIPELINE */
    private String refType;

    /** 关联外部实例ID，如 pipeline_instance.id */
    private String refId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}