package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流节点（DAG 顶点）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node")
public class WorkflowNode extends WorkflowBaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 所属工作流ID */
    private String wfId;

    /** 节点唯一编码（DAG 内） */
    private String nodeCode;

    /** 节点名称 */
    private String nodeName;

    /** 节点描述（工作台展示与交接说明） */
    private String description;

    /** 节点类型: SQL / SHELL / PYTHON / DATA_OP / PIPELINE / QUALITY / DELAY / NESTED */
    private String nodeType;

    /** 节点配置（JSON）：datasourceId/sql/command/script/operator 等 */
    private String configJson;

    /** 画布 X */
    private Integer posX;

    /** 画布 Y */
    private Integer posY;

    /** 超时时间（秒） */
    private Integer timeout;

    /** 失败重试次数 */
    private Integer retryCount;

    /** 重试间隔（秒） */
    private Integer retryInterval;

    /** 是否跳过执行 0:否 1:是；跳过仍会释放下游节点 */
    private Integer skipFlag;

    /** 执行器组标识（用于分组与后续多执行器扩展） */
    private String executorGroup;

    /** 是否允许独立调度 0:否 1:是（允许单独调试/运行该节点） */
    private Integer independentSchedule;

    /** 前置条件表达式，空表示始终执行；不满足时节点跳过并释放下游 */
    private String precondition;

    /** 节点级参数（JSON），覆盖工作流级参数 */
    private String nodeParams;
}