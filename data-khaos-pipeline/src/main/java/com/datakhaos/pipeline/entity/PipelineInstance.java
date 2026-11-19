package com.datakhaos.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管道执行实例
 */
@Data
@TableName("pipeline_instance")
public class PipelineInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String taskId;

    /** 执行引擎 */
    private String engine;

    /** MANUAL/CRON */
    private String triggerType;

    /** 0=运行中 1=成功 2=失败 */
    private Integer status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    @TableField(value = "`rows`")
    private Long rows;

    private String errorMessage;

    /** 执行 worker 标识 */
    private String worker;

    /** 关联工作流运行ID（WORKFLOW 触发时写入） */
    private String workflowRunId;

    /** 关联工作流节点运行ID（WORKFLOW 触发时写入） */
    private String workflowNodeRunId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}